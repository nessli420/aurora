package com.aurora.music.foryou

import com.aurora.music.data.PlayEvent
import com.aurora.music.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MixBuilderTest {
    private val hour = MixClock.HOUR
    private val day = MixClock.DAY
    // 2026-11-18 20:00 utc, an evening
    private val now = 1_795_032_000_000L
    private val clock = MixClock(now, 0)

    private fun song(id: String, artist: String, genre: String = "", liked: Boolean = false, seconds: Int = 200) =
        Song(id, "Title $id", artist, "Album of $artist", "", seconds, liked = liked, genre = genre, artistId = artist)

    private fun play(song: Song, at: Long, listenedMs: Long? = null) =
        PlayEvent(song.id, song.title, song.artist, song.album, "", song.artistId, "", song.durationSec, at, listenedMs)

    private val rock = listOf("Rock A", "Rock B", "Rock C").flatMap { artist -> (1..12).map { song("${artist.takeLast(1)}r$it", artist) } }
    private val calm = listOf("Calm A", "Calm B", "Calm C").flatMap { artist -> (1..12).map { song("${artist.takeLast(1)}c$it", artist) } }
    private val library = rock + calm

    // rock in the evenings and calm in the mornings, never in the same sitting
    private fun twoTastes(days: Int = 20): List<PlayEvent> = (1..days).flatMap { d ->
        val evening = now - d * day
        val morning = evening - 12 * hour
        (0 until 6).map { i -> play(rock[(d * 5 + i * 7) % rock.size], evening + i * 240_000L) } +
            (0 until 6).map { i -> play(calm[(d * 3 + i * 5) % calm.size], morning + i * 240_000L) }
    }

    private fun build(events: List<PlayEvent> = twoTastes(), known: List<Song> = library, candidates: List<Song> = emptyList(),
        vectors: Map<String, FloatArray> = emptyMap(), shown: Map<String, Map<String, Long>> = emptyMap(), at: MixClock = clock,
        seed: Long = 7, hidden: (Song) -> Boolean = { false }, stations: List<Station> = emptyList()) =
        MixBuilder.build(input(events, known, candidates, vectors, shown, at, seed, hidden, stations))

    private fun input(events: List<PlayEvent> = twoTastes(), known: List<Song> = library, candidates: List<Song> = emptyList(),
        vectors: Map<String, FloatArray> = emptyMap(), shown: Map<String, Map<String, Long>> = emptyMap(), at: MixClock = clock,
        seed: Long = 7, hidden: (Song) -> Boolean = { false }, stations: List<Station> = emptyList()) =
        MixInput(events, at, known, known.filter { it.liked }.mapTo(HashSet()) { it.id }, candidates, vectors, shown = shown, hidden = hidden, seed = seed, stations = stations)

    private fun List<GeneratedMix>.slot(name: String) = firstOrNull { it.slot == name }

    @Test fun theDayIsCutIntoFourPartsAndStartsWithTheMorning() {
        assertEquals(DayPart.NIGHT, DayPart.of(4))
        assertEquals(DayPart.MORNING, DayPart.of(5))
        assertEquals(DayPart.MORNING, DayPart.of(11))
        assertEquals(DayPart.AFTERNOON, DayPart.of(12))
        assertEquals(DayPart.EVENING, DayPart.of(17))
        assertEquals(DayPart.NIGHT, DayPart.of(22))
        assertEquals(DayPart.NIGHT, DayPart.of(0))

        assertEquals(DayPart.EVENING, clock.part)
        assertEquals(2 * hour, clock.untilNextPart)
        val lateNight = MixClock(now + 7 * hour, 0)
        assertEquals(DayPart.NIGHT, lateNight.part)
        assertEquals("03:00 still belongs to the evening before", clock.day, lateNight.day)
        assertEquals(2 * hour, lateNight.untilNextPart)
        assertEquals(clock.day + 1, MixClock(now + 9 * hour, 0).day)
        assertEquals("the zone moves the boundaries with it", DayPart.NIGHT, MixClock(now, 3 * hour).part)
    }

    @Test fun dailyMixesFollowWhatIsHeardTogether() {
        val daily = build().filter { it.kind == MixKind.DAILY }
        assertEquals(2, daily.size)
        daily.forEach { mix ->
            assertTrue(mix.songs.size >= MixBuilder.MIN)
            assertEquals("one taste per mix: ${mix.songs.map { it.artist }.distinct()}", 1, mix.songs.map { it.artist.substringBefore(' ') }.distinct().size)
            assertEquals(mix.songs.size, mix.songs.distinctBy { it.id }.size)
            assertTrue(mix.artists.isNotEmpty())
        }
        assertNotEquals(daily[0].songs.first().artist.substringBefore(' '), daily[1].songs.first().artist.substringBefore(' '))
        assertTrue(daily[0].songs.map { it.id }.intersect(daily[1].songs.map { it.id }.toSet()).isEmpty())
    }

    @Test fun theTimeMixIsWhatPlaysAtThatHour() {
        val mixes = build()
        val evening = mixes.slot("time-evening")!!
        val morning = mixes.slot("time-morning")!!
        assertEquals(DayPart.EVENING, evening.part)
        assertTrue(evening.songs.all { it.artist.startsWith("Rock") })
        assertTrue(morning.songs.all { it.artist.startsWith("Calm") })
        assertNull("nothing was ever played at night", mixes.slot("time-night"))
        assertNull(mixes.slot("time-afternoon"))
    }

    @Test fun recommendedLeavesOutWhatIsAlreadyOnRepeat() {
        val shelf = (1..30).map { song("shelf$it", if (it % 2 == 0) "Rock A" else "Calm B") }
        val stranger = (1..10).map { song("stranger$it", "Nobody Known") }
        val heardOnce = shelf.first()
        val mix = build(events = twoTastes() + play(heardOnce, now - 2 * day), known = library + shelf + stranger).slot("recommended")!!
        val ids = mix.songs.map { it.id }.toSet()
        val played = twoTastes().groupingBy { it.songId }.eachCount()
        assertTrue("nothing with two or more plays", ids.none { (played[it] ?: 0) >= 2 })
        assertFalse("played two days ago is not rare", heardOnce.id in ids)
        assertTrue("an artist with no tie to the taste has no place", ids.none { it.startsWith("stranger") })
        assertTrue(ids.any { it.startsWith("shelf") })
    }

    @Test fun analysisLetsAnUnknownArtistIn() {
        val stranger = (1..24).map { song("stranger$it", "Nobody ${it % 8}") }
        fun vector(base: Float, i: Int) = FloatArray(51) { d -> base * (if (d % 2 == 0) 1f else -1f) + (i * 31 + d * 7) % 13 * 0.01f }
        val vectors = rock.withIndex().associate { (i, s) -> s.id to vector(1f, i) } + calm.withIndex().associate { (i, s) -> s.id to vector(-1f, i) } +
            stranger.withIndex().associate { (i, s) -> s.id to vector(1f, i + 50) }
        assertNull("no tags, no plays, no analysis: not eligible", build(known = library + stranger).slot("recommended"))
        val mix = build(known = library + stranger, vectors = vectors).slot("recommended")!!
        assertTrue(mix.songs.any { it.id.startsWith("stranger") })
    }

    @Test fun discoverHoldsOnlyWhatWasNeverHeard() {
        val fresh = (1..46).map { song("new$it", if (it <= 6) "Rock A" else "Newcomer ${it % 36}") }
        val junk = listOf(song("mix1", "DJ Somebody", seconds = 3600), Song("mix2", "90s Hip Hop Mix Vol 3", "Various", "", "", 200))
        val likedElsewhere = song("liked1", "Newcomer 1", liked = true)
        val mix = build(known = library + likedElsewhere, candidates = fresh + fresh.take(5) + library.take(10) + junk + likedElsewhere,
            hidden = { it.id == "new7" }).slot("discover")!!
        val ids = mix.songs.map { it.id }
        assertTrue(ids.all { it.startsWith("new") })
        assertFalse("hidden songs stay out", "new7" in ids)
        assertTrue(mix.songs.groupingBy { it.artist }.eachCount().values.all { it <= 2 })
        assertTrue(mix.songs.size >= MixBuilder.MIN_DISCOVER)
    }

    @Test fun aRecordingHeardOnAnotherSourceIsNotNew() {
        val twin = Song("other-source:1", rock.first().title, rock.first().artist, "", "", 200)
        val fresh = (1..20).map { song("new$it", "Newcomer ${it % 10}") }
        val mix = build(candidates = fresh + twin).slot("discover")!!
        assertFalse(twin.id in mix.songs.map { it.id })
    }

    @Test fun theSameSeedAndDayGiveTheSameMixes() {
        val fresh = (1..60).map { song("new$it", "Newcomer ${it % 20}") }
        val first = build(candidates = fresh)
        val again = build(candidates = fresh)
        assertEquals(first.map { it.id to it.songs.map { s -> s.id } }, again.map { it.id to it.songs.map { s -> s.id } })
        assertTrue(first.isNotEmpty())

        val tomorrow = build(candidates = fresh, at = MixClock(now + day, 0))
        assertNotEquals(first.slot("discover")!!.id, tomorrow.slot("discover")!!.id)
        assertNotEquals(first.slot("discover")!!.songs.map { it.id }, tomorrow.slot("discover")!!.songs.map { it.id })
        assertNotEquals(first.slot("discover")!!.songs.map { it.id }, build(candidates = fresh, seed = 8).slot("discover")!!.songs.map { it.id })
    }

    @Test fun whatWasJustShownRestsWhileThePoolAllows() {
        val fresh = (1..90).map { song("new$it", "Newcomer ${it % 45}") }
        val today = build(candidates = fresh).slot("discover")!!
        val shown = mapOf("discover" to today.songs.associate { mixTrackKey(it) to clock.day })
        val next = build(candidates = fresh, shown = shown, at = MixClock(now + day, 0)).slot("discover")!!
        assertTrue("a big pool repeats nothing", next.songs.map { it.id }.intersect(today.songs.map { it.id }.toSet()).isEmpty())

        val few = fresh.take(34)
        val small = build(candidates = few).slot("discover")!!
        val later = build(candidates = few, shown = mapOf("discover" to small.songs.associate { mixTrackKey(it) to clock.day }), at = MixClock(now + day, 0)).slot("discover")!!
        val rested = few.map { it.id } - small.songs.map { it.id }.toSet()
        assertEquals(4, rested.size)
        assertTrue("the ones that sat out come first", later.songs.map { it.id }.containsAll(rested))
        assertEquals("and yesterday's fill up rather than leave the mix short", MixBuilder.TARGET, later.songs.size)

        val afterTheRest = build(candidates = fresh, shown = shown, at = MixClock(now + 9 * day, 0)).slot("discover")!!
        assertEquals(build(candidates = fresh, at = MixClock(now + 9 * day, 0)).slot("discover")!!.songs.map { it.id }, afterTheRest.songs.map { it.id })
    }

    @Test fun aQuickSkipIsNotAPlay() {
        val skipped = song("skipme", "Rock A")
        val events = twoTastes() + (1..8).map { play(skipped, now - it * day - hour, listenedMs = 4_000) }
        val taste = MixTaste(events, clock)
        assertNull(taste.plays[mixTrackKey(skipped)])
        assertEquals(8, taste.skips[mixTrackKey(skipped)])
    }

    @Test fun littleHistoryFallsBackToLikesOrShowsNothing() {
        assertTrue(build(events = emptyList(), known = library.take(5).map { it.copy(liked = true) }).isEmpty())

        val liked = library.map { it.copy(liked = true) }
        val mixes = build(events = emptyList(), known = liked)
        assertEquals("likes alone give one mix and the unplayed rest, no time of day and nothing to discover", listOf("daily-1", "recommended"), mixes.map { it.slot })
        assertTrue(mixes.all { it.songs.size >= MixBuilder.MIN })
        assertTrue(mixes[0].songs.map { it.id }.intersect(mixes[1].songs.map { it.id }.toSet()).isEmpty())

        val aFewPlays = build(events = twoTastes(days = 1), known = library)
        assertTrue("twelve plays are no base for a time of day", aFewPlays.none { it.kind == MixKind.TIME })
    }

    @Test fun anArtistStationHoldsTheirSongsFromEverySourceAndTheArtistsAround() {
        val mine = library.filter { it.artist == "Rock A" }
        val sameOnAnotherSource = mine.take(4).map { it.copy(id = "other:${it.id}") }
        val onlyThere = (1..10).map { song("other:exclusive$it", "Rock A") }
        val featured = Song("other:feat", "Guest Spot", "Somebody Else, Rock A", "", "", 200)
        val namesake = song("other:namesake", "Rock A Tribute Band")
        val around = (1..40).map { song("around$it", "Neighbour ${it % 20}") }
        val station = Station("Rock A", sameOnAnotherSource + onlyThere + featured + namesake, around + around.take(6) + mine.take(2) + Song("mixtape", "Rock Mix Vol 2", "DJ Nobody", "", "", 4000))
        val mix = build(stations = listOf(station)).slot("artist-1")!!

        assertEquals(MixKind.STATION, mix.kind)
        assertEquals("Rock A", mix.artists.first())
        assertTrue("the others named are the artists around: ${mix.artists}", mix.artists.drop(1).all { it.startsWith("Neighbour") })
        assertTrue("it opens with the artist", mix.songs.first().artist.contains("Rock A"))
        val own = mix.songs.filter { it.artist.contains("Rock A") }
        assertEquals("a dozen of theirs, the rest from around them", 12, own.size)
        assertEquals(MixBuilder.TARGET, mix.songs.size)
        assertTrue("songs only the other source has are in", own.any { it.id.startsWith("other:exclusive") })
        assertTrue("a recording both sources have comes once, as the library's copy", own.none { it.id in sameOnAnotherSource.map { s -> s.id } })
        assertEquals(mix.songs.size, mix.songs.distinctBy { it.title to it.artist }.size)
        assertTrue("another artist whose name starts the same is not them", mix.songs.none { it.id == "other:namesake" })
        assertTrue(creditNames("Somebody Else, Rock A", "rock a") && creditNames("Rock A & Friend", "rock a") && creditNames("Friend feat. Rock A", "rock a"))
        assertFalse(creditNames("Rock A Tribute Band", "rock a") || creditNames("Rocka", "rock a"))
        assertTrue(mix.songs.none { it.id == "mixtape" })
        assertTrue("two per neighbour", mix.songs.filter { it.artist.startsWith("Neighbour") }.groupingBy { it.artist }.eachCount().values.all { it <= 2 })

        assertNull("three songs are not a station", build(stations = listOf(Station("Stranger", (1..2).map { song("s$it", "Stranger") }, around))).slot("artist-1"))
        val alone = build(stations = listOf(Station("Rock A", onlyThere, emptyList()))).slot("artist-1")!!
        assertTrue("no source plays anything next to them: the station is all theirs", alone.songs.all { it.artist == "Rock A" })
        assertTrue(alone.songs.size >= MixBuilder.MIN)
    }

    @Test fun stationsGoToTheMostLovedArtistsAndMoveOnTheNextDay() {
        val many = (1..12).flatMap { a -> (1..4).map { song("a${a}s$it", "Artist $a", liked = true) } }
        val seeds = MixBuilder.prepare(input(events = emptyList(), known = many)).stationArtists
        assertEquals(MixBuilder.MAX_STATIONS, seeds.size)
        assertEquals(seeds.map { it.artist }, MixBuilder.prepare(input(events = emptyList(), known = many)).stationArtists.map { it.artist })
        assertTrue(seeds.all { seed -> seed.songs.size == 4 && seed.songs.all { it.artist == seed.artist } })

        val shown = mapOf(MixBuilder.STATION_ARTISTS to seeds.associate { mixArtistKey(it.artist) to clock.day })
        val next = MixBuilder.prepare(input(events = emptyList(), known = many, shown = shown, at = MixClock(now + day, 0))).stationArtists
        assertTrue("yesterday's artists sit out while others wait: ${next.map { it.artist }}", next.map { it.artist }.intersect(seeds.map { it.artist }.toSet()).isEmpty())

        val favourite = MixBuilder.prepare(input(known = library + many.take(4))).stationArtists.map { it.artist }
        assertTrue("played artists come before one with a few likes: $favourite", favourite.count { it.startsWith("Rock") || it.startsWith("Calm") } >= 4)
        assertTrue(MixBuilder.prepare(MixInput(emptyList(), clock, many, kinds = setOf(MixKind.DAILY))).stationArtists.isEmpty())
    }

    @Test fun aPlayingEditionIsFollowedByItsSlotOrTheNewTimeOfDay() {
        val tiles = listOf(GeneratedMix("time-evening", MixKind.TIME, 2), GeneratedMix("daily-1", MixKind.DAILY, 2),
            GeneratedMix("daily-2", MixKind.DAILY, 2), GeneratedMix("artist-1", MixKind.STATION, 2))
        assertNull("still the edition on home", mixSuccessor("daily-2@2", tiles))
        assertEquals("time-evening@2", mixSuccessor("time-afternoon@2", tiles)?.id)
        assertEquals("time-evening@2", mixSuccessor("time-evening@1", tiles)?.id)
        assertEquals("daily-2@2", mixSuccessor("daily-2@1", tiles)?.id)
        assertNull("the next day's station is another artist's", mixSuccessor("artist-1@1", tiles))
        assertNull("its row is gone", mixSuccessor("discover@1", tiles))
        assertNull(mixSuccessor("daily-1@1", emptyList()))
    }

    @Test fun theNewEditionGoesBehindThePlayingSongAndWhatTheListenerQueued() {
        fun from(mix: String, vararg ids: String) = ids.map { song(it, "Rock A").copy(playbackCollection = mixCollection(mix)) }
        val old = from("time-afternoon@2", "a", "b", "c", "d")
        val mine = song("mine", "Calm A")
        val pick = song("pick", "Calm B")
        val queue = old.take(2) + mine + old.drop(2) + pick
        assertEquals("time-afternoon@2", queueMix(queue, 1))
        assertNull(queueMix(queue, -1))
        assertNull("only the listener's own songs are left", queueMix(listOf(mine, pick), 0))
        assertNull("another collection is not a mix", queueMix(listOf(mine.copy(playbackCollection = com.aurora.music.data.PlaybackCollectionIdentity("playlist:abc"))), 0))

        val behind = mixFollowUp(queue, 1, setOf("pick"), from("time-evening@2", "a", "x", "mine", "y", "b"))
        assertEquals("heard songs, the playing one and autoplay's picks are not queued again", listOf("mine", "x", "y"), behind.map { it.id })
        val after = queue.take(2) + behind
        assertEquals("time-evening@2", queueMix(after, 1))
        assertEquals("on its last song a queue still knows its mix", "time-afternoon@2", queueMix(old, 3))
        assertTrue("nothing new leaves the queue alone", mixFollowUp(queue, 1, emptySet(), from("time-evening@2", "a", "b")).isEmpty())
    }

    @Test fun switchedOffKindsAreNotBuilt() {
        val mixes = MixBuilder.build(MixInput(twoTastes(), clock, library, kinds = setOf(MixKind.TIME)))
        assertTrue(mixes.isNotEmpty())
        assertTrue(mixes.all { it.kind == MixKind.TIME })
    }
}
