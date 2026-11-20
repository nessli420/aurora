package com.aurora.music.data

import com.aurora.music.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class HistoryImportTest {
    private val fixtures = File(requireNotNull(javaClass.getResource("/history-import")).toURI())
    private val root: File = Files.createTempDirectory("aurora-history-import").toFile()
    private val now = Instant.parse("2026-11-20T12:00:00Z").toEpochMilli()

    @After fun tearDown() { root.deleteRecursively() }

    private fun read(vararg paths: String): ExportRead =
        HistoryExports.readAll(paths.map { path -> File(fixtures, path).let { f -> f.name to { f.inputStream() as InputStream } } })

    private fun at(text: String) = Instant.parse(text).toEpochMilli()

    private fun plan(read: ExportRead, history: List<PlayEvent> = emptyList(), library: List<Song> = emptyList(), importedAt: Long = now, room: Int = PlayHistoryStore.MAX) =
        HistoryImports.plan(read.plays, read.skipped, read.files, history, library, importedAt, now, room)

    @Test fun spotifyAccountDataReadsUtcEndTimesToTheMinuteAndLeavesPodcastsOut() {
        val read = read("spotify-account/StreamingHistory_music_0.json", "spotify-account/StreamingHistory_podcast_0.json")
        assertEquals(listOf("Glass Harbor", "Slow Comet", "Paper Tides"), read.plays.map { it.title })
        val glass = read.plays.first()
        assertEquals(at("2024-03-01T21:07:00Z"), glass.at)
        assertTrue(glass.atEnd)
        assertEquals(201_344L, glass.listenedMs)
        assertEquals("", glass.album)
        assertEquals(mapOf(ImportSkip.NOT_PLAYED to 1, ImportSkip.NO_DATE to 1, ImportSkip.PODCAST to 1), read.skipped)
        assertEquals(listOf("StreamingHistory_music_0.json", "StreamingHistory_podcast_0.json"), read.files)
    }

    @Test fun spotifyExtendedHistoryKeepsSkipsAndShuffleAndLeavesEpisodesBooksVideosAndPrivatePlaysOut() {
        val read = read("spotify-extended/Streaming_History_Audio_2019-2024_0.json", "spotify-extended/Streaming_History_Video_2024.json")
        assertEquals(listOf("Old Song", "Glass Harbor", "Slow Comet", "Paper Tides", "Glass Harbor", "Orbit Lines", "Orbit Lines"), read.plays.map { it.title })
        assertEquals(mapOf(ImportSkip.PODCAST to 1, ImportSkip.AUDIOBOOK to 1, ImportSkip.PRIVATE to 1, ImportSkip.NO_TRACK to 1,
            ImportSkip.NOT_PLAYED to 1, ImportSkip.VIDEO to 1), read.skipped)
        val byTime = read.plays.associateBy { it.at }
        assertEquals(PlayEnding.COMPLETED, byTime.getValue(at("2024-03-01T21:07:42Z")).ending)
        assertEquals(PlayEnding.SKIPPED, byTime.getValue(at("2024-03-01T21:10:05Z")).ending)
        // reason "remote" says nothing, the skipped flag does
        assertEquals(PlayEnding.SKIPPED, byTime.getValue(at("2024-03-05T22:31:00Z")).ending)
        val unstated = byTime.getValue(at("2024-03-05T22:40:00Z"))
        assertNull(unstated.ending); assertNull(unstated.shuffle)
        assertEquals(true, byTime.getValue(at("2024-03-05T22:30:00Z")).shuffle)
        assertEquals("0SynthGlassHarbor00001", byTime.getValue(at("2024-03-05T22:30:00Z")).spotifyTrack)
        assertEquals("Harbor Lights", byTime.getValue(at("2024-03-05T22:30:00Z")).album)
    }

    @Test fun accountDataAndExtendedHistoryOfTheSamePlaysCountOnceAndKeepTheRicherRow() {
        val preview = plan(read("spotify-account/StreamingHistory_music_0.json", "spotify-extended/Streaming_History_Audio_2019-2024_0.json"))
        assertEquals(7, preview.plays.size)
        assertEquals(3, preview.skipped[ImportSkip.DUPLICATE])
        val glass = preview.plays.filter { it.title == "Glass Harbor" }.sortedBy { it.timestamp }
        assertEquals(listOf(at("2024-03-01T21:07:42Z"), at("2024-03-05T22:30:00Z")), glass.map { it.timestamp })
        assertEquals("Harbor Lights", glass.first().album)
    }

    @Test fun reimportingTheSameExportAddsNothing() {
        val read = read("spotify-extended/Streaming_History_Audio_2019-2024_0.json")
        val first = plan(read, importedAt = now - 1000)
        val second = plan(read, history = first.plays)
        assertTrue(second.plays.isEmpty())
        assertEquals(7, second.skipped[ImportSkip.ALREADY_IMPORTED])
        // overlapping account data from the same account adds nothing either
        val overlap = plan(read("spotify-account/StreamingHistory_music_0.json"), history = first.plays)
        assertTrue(overlap.plays.isEmpty())
        assertEquals(3, overlap.skipped[ImportSkip.ALREADY_IMPORTED])
    }

    @Test fun playsAuroraRecordedItselfAreNotCountedAgainButRepeatsAreKept() {
        // aurora stamps a play when it starts; spotify stamps the same stream where it ended
        val own = PlayEvent("sp-1", "Glass Harbor", "Nova Pilot", "Harbor Lights", "", "", "", 201, at("2024-03-05T22:26:45Z"), 195_000)
        val preview = plan(read("spotify-extended/Streaming_History_Audio_2019-2024_0.json"), history = listOf(own))
        assertEquals(1, preview.skipped[ImportSkip.IN_AURORA_HISTORY])
        assertEquals(6, preview.plays.size)
        assertTrue(preview.plays.any { it.title == "Glass Harbor" && it.timestamp == at("2024-03-01T21:07:42Z") })
        val later = own.copy(timestamp = at("2024-03-05T23:30:00Z"))
        assertEquals(7, plan(read("spotify-extended/Streaming_History_Audio_2019-2024_0.json"), history = listOf(later)).plays.size)
    }

    @Test fun appleJoinsRowsSplitAtPausesAndTakesArtistsOnlyWhereTheSameExportNamesOne() {
        val read = read("apple/Apple Music Play Activity.csv", "apple/Apple Music - Play History Daily Tracks.csv", "apple/Apple Music Library Tracks.json")
        val plays = read.plays.sortedBy { it.at }
        assertEquals(listOf("Old Apple Song", "Glass Harbor", "Quiet Engine", "Hello, \"World\"", "Mystery Track", "Quiet Engine"), plays.map { it.title })
        assertEquals(listOf("Archive Band", "Nova Pilot", "Kite Theory", "Lumen Field", "", ""), plays.map { it.artist })
        val joined = plays[2]
        assertEquals(at("2024-04-01T18:10:00Z"), joined.at)
        assertEquals(240_000L, joined.listenedMs)
        assertEquals(PlayEnding.COMPLETED, joined.ending)
        assertEquals(true, joined.shuffle)
        assertEquals(240_000L, joined.trackMs)
        assertFalse(plays[1].atEnd)
        assertEquals(PlayEnding.SKIPPED, plays[3].ending)
        assertNull(plays[3].shuffle)
        assertEquals(mapOf(ImportSkip.NOT_PLAYED to 1, ImportSkip.NOT_A_PLAY to 3, ImportSkip.VIDEO to 1), read.skipped)
        assertEquals(listOf("Apple Music Play Activity.csv"), read.files)
        val preview = plan(read)
        assertEquals(2, preview.unknownArtist)
        assertEquals(200, preview.plays.first { it.title == "Glass Harbor" }.durationSec)
    }

    @Test fun appleWithoutItsOtherFilesKeepsPlaysWithoutAnArtist() {
        val read = read("apple/Apple Music Play Activity.csv")
        assertTrue(read.plays.all { it.artist.isEmpty() })
        assertEquals(6, plan(read).unknownArtist)
    }

    @Test fun youtubeWatchHistoryHasNoPlayLengthAndLeavesVideosAdsAndRemovedEntriesOut() {
        val read = read("youtube/watch-history.json")
        assertEquals(listOf("Glass Harbor", "Paper Tides", "Glass Harbor"), read.plays.map { it.title })
        assertEquals(listOf("Nova Pilot", "Lumen Field", "Nova Pilot"), read.plays.map { it.artist })
        assertTrue(read.plays.all { it.listenedMs == null && it.trackMs == null && !it.atEnd })
        assertEquals(at("2024-05-02T20:00:00.123Z"), read.plays[2].at)
        assertEquals(mapOf(ImportSkip.VIDEO to 1, ImportSkip.AD to 1, ImportSkip.REMOVED to 2, ImportSkip.NOT_A_PLAY to 2), read.skipped)
    }

    @Test fun youtubeHtmlReadsStatedZonesAndLeavesAmbiguousOnesOut() {
        val read = read("youtube/watch-history.html")
        assertEquals(listOf("Glass Harbor", "Rock & Roll 'Live'", "Slow Comet"), read.plays.map { it.title })
        assertEquals(listOf("Nova Pilot", "The Synth Band", "Nova Pilot"), read.plays.map { it.artist })
        assertEquals(listOf(at("2024-05-01T08:15:02Z"), at("2017-01-05T20:01:23Z"), at("2024-02-07T23:30:00Z")), read.plays.map { it.at })
        assertEquals(mapOf(ImportSkip.NO_DATE to 1, ImportSkip.VIDEO to 1), read.skipped)
        // the same plays from the json and the html of one takeout count once
        val both = plan(read("youtube/watch-history.json", "youtube/watch-history.html"))
        assertEquals(5, both.plays.size)
        assertEquals(1, both.skipped[ImportSkip.DUPLICATE])
    }

    @Test fun youtubeWordingInAnotherLanguageIsFoundFromTheTitlesThemselves() {
        val titles = ('A'..'Y').map { "Has escuchado ${it}ola $it" }
        val strip = YouTubeTitles.stripper(titles)
        assertEquals("Dola D", strip(titles[3]))
        // too few titles to tell the wording from the songs, so nothing is cut
        assertEquals("Has escuchado Aola A", YouTubeTitles.stripper(titles.take(5))(titles[0]))
        assertEquals("Hello", YouTubeTitles.stripper(listOf("Watched Hello", "Watched World"))("Watched Hello"))
        assertEquals("Lied", YouTubeTitles.stripper(listOf("Lied angesehen", "Song angesehen"))("Lied angesehen"))
        assertEquals("Synth Artist", YouTubeTitles.artist("Synth Artist - Topic"))
    }

    @Test fun zipsAndZipsInsideZipsAreRead() {
        fun zip(entries: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z -> entries.forEach { (name, bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
        }.toByteArray()
        fun bytes(path: String) = File(fixtures, path).readBytes()
        val crlf = String(bytes("apple/Apple Music Play Activity.csv")).replace("\r\n", "\n").replace("\n", "\r\n").toByteArray()
        val inner = zip(listOf("Apple_Media_Services/Apple Music Activity/Apple Music Play Activity.csv" to crlf,
            "Apple_Media_Services/Apple Music Activity/Apple Music - Play History Daily Tracks.csv" to bytes("apple/Apple Music - Play History Daily Tracks.csv")))
        val outer = zip(listOf("Apple_Media_Services.zip" to inner, "__MACOSX/._Apple_Media_Services.zip" to ByteArray(8)))
        val spotify = zip(listOf("Spotify Extended Streaming History/Streaming_History_Audio_2019-2024_0.json" to bytes("spotify-extended/Streaming_History_Audio_2019-2024_0.json"),
            "Spotify Extended Streaming History/ReadMeFirst_ExtendedStreamingHistory.pdf" to ByteArray(100), "Spotify Account Data/Userdata.json" to "{\"username\":\"x\"}".toByteArray(),
            "Spotify Account Data/SearchQueries.json" to "[{\"platform\":\"x\",\"searchTime\":\"y\",\"searchQuery\":\"z\"}]".toByteArray()))
        val read = HistoryExports.readAll(listOf("Apple Media Services information.zip" to { ByteArrayInputStream(outer) as InputStream },
            "my_spotify_data.zip" to { ByteArrayInputStream(spotify) as InputStream }))
        assertEquals(6, read.plays.count { it.service == HistoryService.APPLE_MUSIC })
        assertEquals(7, read.plays.count { it.service == HistoryService.SPOTIFY })
        assertEquals("Kite Theory", read.plays.first { it.title == "Quiet Engine" && it.at == at("2024-04-01T18:10:00Z") }.artist)
    }

    @Test fun matchesLinkOnlyWhenOneRecordingFitsAndKeepTheExportsOwnText() {
        val bySpotify = Song("0\u0001" + "0SynthGlassHarbor00001", "Glass Harbour", "Nova Pilot", "Harbor Lights", "art://glass", 201, albumId = "al-1", artistId = "ar-1")
        val tides = Song("nd-tides", "Paper Tides", "Lumen Field", "Tidewater", "art://tides", 185, albumId = "al-2", artistId = "ar-2")
        val orbitA = Song("nd-orbit-a", "Orbit Lines", "Lumen Field", "Tidewater", "", 200)
        val orbitB = Song("nd-orbit-b", "Orbit Lines", "Lumen Field", "Orbit Lines (Single)", "", 205)
        val preview = plan(read("spotify-extended/Streaming_History_Audio_2019-2024_0.json"), library = listOf(bySpotify, tides, orbitA, orbitB))
        val glass = preview.plays.first { it.title == "Glass Harbor" }
        assertEquals(bySpotify.id, glass.songId)
        assertEquals("Glass Harbor", glass.title)
        assertEquals("art://glass", glass.artworkUrl)
        assertEquals("al-1", glass.albumId)
        assertEquals("nd-tides", preview.plays.first { it.title == "Paper Tides" }.songId)
        // the spotify row names album "Tidewater", which only one copy is on
        assertEquals("nd-orbit-a", preview.plays.first { it.title == "Orbit Lines" }.songId)
        val old = preview.plays.first { it.title == "Old Song" }
        assertTrue(isImportedSongId(old.songId))
        assertEquals("", old.artworkUrl)
        assertEquals(5, preview.linked)
        // without an album or a length two copies of one title stay unlinked
        val youtube = plan(read("youtube/watch-history.json"), library = listOf(orbitA, orbitB, tides, tides.copy(id = "jf-tides", album = "Live")))
        assertTrue(youtube.plays.all { isImportedSongId(it.songId) })
    }

    @Test fun playsWithoutAStatedLengthCountAsPlaysButAddNoMinutes() {
        val preview = plan(read("youtube/watch-history.json"))
        assertEquals(3, preview.unknownTime)
        val window = RecapWindow.containing(RecapPeriod.MONTH, LocalDate.of(2024, 5, 1))
        val recap = ListeningRecaps.build(preview.plays, window, ZoneId.of("UTC"))
        assertEquals(3, recap.plays)
        assertEquals(0L, recap.millis)
        assertEquals(3, recap.unknownTime)
        assertFalse(recap.estimated)
        assertTrue(recap.songs.all { it.id.isEmpty() })
        // aurora's own rows from before play times were kept still use the track length
        val legacy = PlayEvent("x", "Old", "Artist", "", "", "", "", 200, at("2024-05-03T10:00:00Z"))
        val mixed = ListeningRecaps.build(preview.plays + legacy, window, ZoneId.of("UTC"))
        assertEquals(200_000L, mixed.millis); assertTrue(mixed.estimated)
        assertNull(preview.plays.first().toImportedPlay("android"))
    }

    @Test fun periodsOnlyImportedPlaysFillAreNotNewRecaps() {
        val imported = plan(read("spotify-extended/Streaming_History_Audio_2019-2024_0.json")).plays
        val own = PlayEvent("own", "Mine", "Me", "", "", "", "", 200, at("2024-03-05T09:00:00Z"), 120_000)
        val today = LocalDate.of(2026, 11, 20)
        val utc = ZoneId.of("UTC")
        val quiet = ListeningRecaps.importedOnly(imported + own, today, utc)
        assertTrue("YEAR:2019-01-01" in quiet)
        assertTrue("MONTH:2024-03-01" !in quiet)
        assertTrue("DAY:2024-03-05" !in quiet)
        assertTrue("DAY:2024-03-01" in quiet)
        assertTrue(ListeningRecaps.importedOnly(listOf(own), today, utc).isEmpty())
    }

    @Test fun timeOfDayComesFromTheExportsUtcStampInTheListenersZone() {
        val preview = plan(read("spotify-account/StreamingHistory_music_0.json", "apple/Apple Music Play Activity.csv"))
        val glass = preview.plays.first { it.title == "Glass Harbor" && HistoryService.of(it.origin) == HistoryService.SPOTIFY }
        val amsterdam = ZoneId.of("Europe/Amsterdam")
        assertEquals(22, Instant.ofEpochMilli(glass.timestamp).atZone(amsterdam).hour)
        val recap = ListeningRecaps.build(preview.plays, RecapWindow.containing(RecapPeriod.YEAR, LocalDate.of(2024, 1, 1)), amsterdam)
        assertEquals(201_344L + 4_200L, recap.hourly[22])
        // apple's 18:00 utc in april is 20:00 in amsterdam's summer time
        assertEquals(200_000L + 240_000L, recap.hourly[20])
        assertEquals(LocalDate.of(2024, 3, 1), Instant.ofEpochMilli(glass.timestamp).atZone(ZoneId.of("Asia/Tokyo")).toLocalDate().minusDays(1))
    }

    @Test fun futureAndMissingDatesAreLeftOutAndTheHistoryLimitIsKept() {
        val rows = listOf(ExportedPlay(HistoryService.SPOTIFY, now + 3 * 86_400_000L, true, "Late", "Artist", "", 60_000),
            ExportedPlay(HistoryService.SPOTIFY, at("2024-01-01T10:00:00Z"), true, "A", "Artist", "", 60_000),
            ExportedPlay(HistoryService.SPOTIFY, at("2024-01-02T10:00:00Z"), true, "B", "Artist", "", 60_000))
        val preview = HistoryImports.plan(rows, emptyMap(), emptyList(), emptyList(), emptyList(), now, now, room = 1)
        assertEquals(listOf("B"), preview.plays.map { it.title })
        assertEquals(1, preview.skipped[ImportSkip.NO_DATE]); assertEquals(1, preview.skipped[ImportSkip.HISTORY_FULL])
    }

    @Test fun undoRemovesExactlyThatImportsPlaysEverywhere() = runBlocking {
        val store = PlayHistoryStore(root)
        val own = Song("own-1", "Own Song", "Me", "Mine", "", 180)
        store.recordListening(own, 90_000, at("2024-03-03T10:00:00Z"))
        store.recordListening(own.copy(id = "own-2"), 60_000, at("2025-01-01T10:00:00Z"))
        val details = FakeDetails()
        val spotify = plan(read("spotify-extended/Streaming_History_Audio_2019-2024_0.json"), store.snapshot(), importedAt = now - 5_000)
        assertEquals(7, HistoryImports.commit(spotify, store, details))
        val youtube = plan(read("youtube/watch-history.json"), store.snapshot(), importedAt = now)
        assertEquals(3, HistoryImports.commit(youtube, store, details))
        assertEquals(12, store.snapshot().size)
        // aurora's own rows keep their order, imports slot in by time
        assertEquals(store.snapshot().sortedByDescending { it.timestamp }, store.snapshot())
        assertEquals(7, details.rows.size)
        assertEquals(PlayEnding.SKIPPED, details.rows.values.first { it.startedAt == at("2024-03-01T21:10:05Z") }.ending)
        assertEquals(true, details.rows.values.first { it.startedAt == at("2024-03-05T22:30:00Z") }.context.shuffle)
        val records = HistoryImports.records(store.snapshot())
        assertEquals(listOf(HistoryService.YOUTUBE_MUSIC, HistoryService.SPOTIFY), records.map { it.service })
        assertEquals(listOf(3, 7), records.map { it.plays })
        assertEquals(at("2019-06-01T12:00:00Z"), records[1].from)

        assertEquals(7, HistoryImports.undo(records[1].origin, store, details))
        assertEquals(listOf("own-2", "imported:nova pilot|glass harbor", "imported:lumen field|paper tides", "imported:nova pilot|glass harbor", "own-1"),
            store.snapshot().map { it.songId })
        assertTrue(details.rows.isEmpty())
        assertEquals(0, HistoryImports.undo(records[1].origin, store, details))
        store.flushNow()
        assertEquals(5, PlayHistoryStore(root).snapshot().size)
        assertEquals(listOf(records[0].origin), HistoryImports.records(PlayHistoryStore(root).snapshot()).map { it.origin })
    }

    @Test fun importedEndingsCountInInsightsNextToAurorasOwn() {
        val imported = PlayEvent("a", "A", "B", "", "", "", "", 0, at("2024-03-01T10:00:00Z"), 120_000, "spotify@1", true, PlayEnding.SKIPPED).toImportedPlay("android")!!
        val own = DetailedPlay("x", at("2024-03-01T11:00:00Z"), at("2024-03-01T11:03:00Z"), 180_000,
            PlayedTrack("b", "B", "C", "", "", "", 180), ending = PlayEnding.COMPLETED)
        val report = Insights.build(listOf(imported, own), emptyMap(), 0, ZoneId.of("UTC"))
        assertEquals(0.5f, report.skips!!, 0.001f)
        assertEquals(1f, report.shuffle!!, 0.001f)
        assertTrue(imported.imported)
        assertNull(imported.context.zone); assertNull(imported.context.output)
    }

    @Test fun aHundredThousandRowExtendedHistoryStreamsThrough() {
        val rows = 150_000
        val stream = object : InputStream() {
            private var row = -1
            private var chunk = "[".toByteArray()
            private var pos = 0
            override fun read(): Int {
                while (pos >= chunk.size) {
                    row++
                    if (row > rows) return -1
                    chunk = if (row == rows) "]".toByteArray() else
                        ((if (row > 0) "," else "") + "{\"ts\":\"${Instant.ofEpochMilli(at("2015-01-01T00:00:00Z") + row * 600_000L)}\",\"ms_played\":${30_000 + row % 200_000}," +
                            "\"master_metadata_track_name\":\"Song ${row % 5000}\",\"master_metadata_album_artist_name\":\"Artist ${row % 300}\"," +
                            "\"master_metadata_album_album_name\":\"Album ${row % 900}\",\"spotify_track_uri\":null,\"episode_name\":null," +
                            "\"spotify_episode_uri\":null,\"reason_end\":\"trackdone\",\"shuffle\":false,\"skipped\":false,\"incognito_mode\":false}").toByteArray()
                    pos = 0
                }
                return chunk[pos++].toInt() and 0xff
            }
        }
        val started = System.nanoTime()
        val read = HistoryExports.readAll(listOf("Streaming_History_Audio_2015-2018_0.json" to { stream }))
        assertEquals(rows, read.plays.size)
        val preview = plan(read)
        assertEquals(rows, preview.plays.size)
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 60_000_000_000L)
        assertTrue(read.plays.map { it.artist }.distinct().size == 300)
        assertTrue(read.plays[0].artist === read.plays[300].artist)
    }

    private class FakeDetails : DetailedListeningRepository {
        val rows = LinkedHashMap<String, DetailedPlay>()
        override fun save(play: DetailedPlay) { rows[play.id] = play }
        override suspend fun summary() = InsightsSummary(rows.size)
        override suspend fun importHistory(events: List<PlayEvent>): Int = events.mapNotNull { it.toImportedPlay("android") }.onEach { rows[it.id] = it }.size
        override suspend fun removeHistory(events: List<PlayEvent>) { events.forEach { rows.remove(it.importedPlayId) } }
        override suspend fun clear() = rows.clear()
        override suspend fun plays(from: Long, to: Long) = rows.values.filter { it.startedAt in from until to }
        override suspend fun firstArtistPlays() = emptyMap<String, Long>()
    }
}
