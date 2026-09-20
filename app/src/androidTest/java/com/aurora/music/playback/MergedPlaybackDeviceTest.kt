package com.aurora.music.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.aurora.music.data.*
import com.aurora.music.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.Assume.assumeTrue

class MergedPlaybackDeviceTest {
    private val helper = PrecisionPlaybackDeviceTest()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as AuroraApplication).container
    @Before fun foreground() = helper.keepTargetForegroundForAudioFocus()
    @After fun cleanup() = helper.removeFixturesAndFinishActivity()

    @Test fun reportedAlbumMatchesLocalFilesAndSearchDurationsAreEnriched() = runBlocking {
        assumeTrue(container.backend is MergedBackend)
        val repository = container.repository
        val library = repository.search("Kanye West", "library")
        assertTrue("Local search is empty", library.songs.isNotEmpty() || library.albums.isNotEmpty())
        assertTrue("Catalogue entries leaked into local search", library.songs.none { it.streamUrl.startsWith("aurora-yt:") })
        val albums = repository.search("Kanye West BULLY", "discovery").albums.filter { it.title.equals("BULLY", true) }
        val tracks = albums.mapNotNull { repository.detail("album", it.id)?.tracks }.maxByOrNull { it.size }.orEmpty()
        assertTrue("The reported album is missing", tracks.size >= 10)
        val candidates = tracks.associate { it.id to repository.playbackCandidates(it) }
        assertTrue("Unmatched BULLY tracks: " + tracks.filter { candidates[it.id].isNullOrEmpty() }.joinToString { it.title }, candidates.values.all { it.isNotEmpty() })
        val runaway = repository.enrichSearchDurations(repository.search("Kanye West Runaway", "discovery"))
        val recordings = runaway.songs.filter { it.title.equals("Runaway (feat. Pusha T)", true) }
        assertTrue("Studio search results are missing", recordings.isNotEmpty())
        assertTrue("Studio search durations are missing", recordings.all { it.durationSec > 0 })
        java.io.File(context.getExternalFilesDir(null), "reported-match-check.txt").writeText(
            "BULLY matching tracks=${tracks.size}; enriched Runaway recordings=${recordings.size}\n" +
                recordings.joinToString("\n") { "${it.title}: ${it.durationSec}s; owned matches=${runBlocking { repository.playbackCandidates(it).size }}" })
        helper.withProcessingFixture(0) { controller, _ ->
            for (song in tracks.filter { it.title == "KING" || it.title.startsWith("FATHER") }) {
                val item = MediaItem.Builder().setMediaId(song.id).setUri(song.streamUrl).setMediaMetadata(
                    MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).setAlbumTitle(song.album)
                        .setExtras(PresetContextPublisher.extras(song)).build()).build()
                val since = System.nanoTime()
                helper.main { controller.setMediaItem(item); controller.prepare(); controller.play() }
                helper.await("reported album plays owned copy with DSP", controller) {
                    val m = container.signalPath.value.measurements
                    helper.main { controller.isPlaying && controller.currentMediaItem?.localConfiguration?.uri?.scheme == "content" } &&
                        m?.afterAvailable == true && (m.after?.measuredAtNanos ?: 0) > since && (m.after?.leftRms ?: 0.0) > 0.000001
                }
                assertEquals(song.id, helper.main { controller.currentMediaItem!!.mediaId })
                helper.main { controller.pause() }
            }
        }
    }

    @Test fun catalogueQueueUsesMatchingFileThroughNativeDspAndPreservesIdentity() {
        helper.withProcessingFixture(0) { controller, _ ->
            val active = requireNotNull(container.backend)
            val ytSession = Session("https://music.youtube.com", "Fixture", "", "", ServerType.YOUTUBE_MUSIC, userId = "fixture")
            val localSession = Session("On this device", "Fixture", "", "", ServerType.LOCAL)
            val original = Song("no-network!", "Preferred fixture", "Aurora QA", "Fixture", "", 30,
                streamUrl = "aurora-yt://video/no-network!", albumId = "catalogue-album")
            val file = helper.tone(48_000, 30)
            val copy = original.copy(id = "local-copy", streamUrl = Uri.fromFile(file).toString(), suffix = "wav",
                sampleRateHz = 48_000, bitDepth = 24, playbackSource = PlaybackSourceIdentity.fromSession(localSession, "local-album"))
            val local = object : MediaBackend by active {
                override val session = localSession
                override suspend fun search(query: String) = SearchResults(songs = listOf(copy))
                override suspend fun matchingSongs(song: Song) = listOf(copy)
            }
            val catalogue = object : MediaBackend by active {
                override val session = ytSession
                override suspend fun search(query: String) = SearchResults(songs = listOf(original))
            }
            val merged = MergedBackend(listOf(local, catalogue), ytSession)
            val backendField = AppContainer::class.java.getDeclaredField("backend").apply { isAccessible = true }
            backendField.set(container, merged)
            try {
                val song = runBlocking { container.repository.search("fixture").songs.single() }
                val item = MediaItem.Builder().setMediaId(song.id).setUri(song.streamUrl).setMediaMetadata(
                    MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).setAlbumTitle(song.album)
                        .setExtras(PresetContextPublisher.extras(song)).build()).build()
                val since = System.nanoTime()
                helper.main { controller.setMediaItem(item); controller.prepare(); controller.play() }
                helper.await("merged recording produces processed PCM", controller) {
                    val measurements = container.signalPath.value.measurements
                    helper.main { controller.isPlaying && controller.currentMediaItem?.localConfiguration?.uri == Uri.fromFile(file) } &&
                        measurements?.afterAvailable == true && (measurements.after?.measuredAtNanos ?: 0) > since &&
                        (measurements.after?.leftRms ?: 0.0) > 0.0001
                }
                val selected = helper.main { controller.currentMediaItem!! }
                assertEquals(song.id, selected.mediaId)
                val displayed = PreferredPlayback.applyTo(song, selected)
                assertEquals(copy.streamUrl, displayed.streamUrl)
                assertEquals(original.streamUrl, selected.mediaMetadata.extras?.getString("aurora.preferred.original"))
                assertEquals(copy.playbackSource, displayed.playbackSource)
                assertEquals(song.albumId, displayed.albumId)
                assertTrue(container.signalPath.value.processing.detail.contains("Custom"))
                assertTrue(container.signalPath.value.processing.detail.contains("Convolution"))
                helper.main { controller.seekTo(10_000) }
                helper.await("preferred recording remains seekable", controller) { helper.main { controller.isPlaying && controller.currentPosition > 10_100 } }
            } finally { helper.main { controller.stop(); controller.clearMediaItems() }; backendField.set(container, active) }
        }
    }

    @Test fun unavailableCopiesFallBackWithoutChangingCatalogueItem() = runBlocking {
        val active = requireNotNull(container.backend)
        val original = Song("fixture", "Unavailable fixture", "Aurora QA", "", "", 30, streamUrl = "aurora-yt://video/fixture")
        val backend = object : MediaBackend by active {
            override suspend fun playbackCandidates(song: Song) = listOf(song.copy(streamUrl = "file:///does-not-exist-aurora.wav"))
        }
        val repository = MusicRepository({ backend }, container.downloadManager)
        val item = MediaItem.Builder().setMediaId(original.id).setUri(original.streamUrl).setMediaMetadata(
            MediaMetadata.Builder().setTitle(original.title).setArtist(original.artist)
                .setExtras(PresetContextPublisher.extras(original)).build()).build()
        assertSame(item, PreferredPlayback.resolve(context, repository, item))
    }

    @Test fun connectedLibraryCopyPlaysFromYoutubeSearch() {
        assumeTrue(container.backend is MergedBackend)
        val selected = runBlocking {
            val report = StringBuilder()
            val all = container.repository.librarySongs(30)
            report.appendLine("Library count=${all.size}; schemes=${all.groupingBy { it.streamUrl.substringBefore(':') }.eachCount()}; offline=${container.offline.value}")
            val sources = MergedBackend::class.java.getDeclaredField("sources").apply { isAccessible = true }.get(container.backend) as List<*>
            report.appendLine("Sources=${sources.map { (it as MediaBackend).session.type }}")
            val own = all.filter {
                (it.streamUrl.startsWith("http") || it.streamUrl.startsWith("content:") || it.streamUrl.startsWith("file:")) && it.durationSec > 10
            }.take(6)
            var match: Song? = null
            for (song in own) {
                val result = container.repository.search("${song.title} ${song.artist}")
                report.appendLine("Owned: ${song.title} | ${song.artist} | ${song.durationSec} | explicit=${song.explicit}")
                result.songs.take(4).forEach { report.appendLine("Catalogue: ${it.title} | ${it.artist} | ${it.durationSec} | explicit=${it.explicit}") }
                match = result.songs.firstOrNull { recordingMatches(song, it) }
                if (match != null) break
            }
            java.io.File(context.getExternalFilesDir(null), "merged-match-check.txt").writeText(report.toString())
            match
        }
        assertNotNull("Could not find a matching catalogue recording in the connected library sample", selected)
        helper.withProcessingFixture(0) { controller, _ ->
            val song = selected!!
            val since = System.nanoTime()
            val item = MediaItem.Builder().setMediaId(song.id).setUri(song.streamUrl).setMediaMetadata(
                MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).setAlbumTitle(song.album)
                    .setExtras(PresetContextPublisher.extras(song)).build()).build()
            helper.main { controller.setMediaItem(item); controller.prepare(); controller.play() }
            helper.await("owned server recording from catalogue search", controller) {
                val m = container.signalPath.value.measurements
                helper.main { controller.isPlaying && controller.currentMediaItem?.localConfiguration?.uri?.scheme in listOf("http", "https", "file", "content") } &&
                    m?.afterAvailable == true && (m.after?.measuredAtNanos ?: 0) > since && (m.after?.leftRms ?: 0.0) > 0.00001
            }
            val actual = helper.main { controller.currentMediaItem!! }
            assertEquals(song.id, actual.mediaId)
            assertNotEquals("aurora-yt", actual.localConfiguration!!.uri.scheme)
            assertTrue(container.signalPath.value.processing.detail.contains("Convolution"))
        }
    }
}
