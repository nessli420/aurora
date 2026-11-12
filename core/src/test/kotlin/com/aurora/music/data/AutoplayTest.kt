package com.aurora.music.data

import com.aurora.music.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoplayTest {
    private fun song(id: String, artist: String = "Artist $id", title: String = "Song $id") = Song(id, title, artist, "", "", 180)

    @Test fun seedsAreTheLatestPlaysOnePerArtist() {
        val played = listOf(song("1", "A"), song("2", "B"), song("3", "B"), song("radio:x", "Station"))
        assertEquals(listOf("3", "1"), Autoplay.seeds(played).map { it.id })
    }

    @Test fun stationsEpisodesAndMixesAreNotFollowed() {
        assertFalse(Autoplay.follows(song("radio:fm")))
        assertFalse(Autoplay.follows(song("podcast:ep")))
        assertFalse(Autoplay.follows(song("aurora-mix:1")))
        assertTrue(Autoplay.follows(song("42")))
    }

    @Test fun picksSkipWhatIsQueuedRepeatsAndCapEachArtist() {
        val queued = listOf(song("1", "A"), song("2", "B", "Same Song"))
        val first = listOf(
            song("1", "A"),                          // already queued
            song("9", "B", "Same Song (Remastered)"),  // a second copy of a queued recording
            song("10", "C"), song("11", "C"), song("12", "C"), // a third from one artist is too many
        )
        val second = listOf(song("20", "D"), song("10", "C"))
        val picked = Autoplay.pick(listOf(first, second), queued, limit = 10)
        assertEquals(listOf("10", "20", "11"), picked.map { it.id })
    }

    @Test fun theNewestSeedLeadsAndTheBatchIsCapped() {
        val first = (1..20).map { song("a$it") }
        val second = (1..20).map { song("b$it") }
        val picked = Autoplay.pick(listOf(first, second), emptyList())
        assertEquals(Autoplay.BATCH, picked.size)
        assertEquals(listOf("a1", "b1", "a2", "b2"), picked.take(4).map { it.id })
    }

    @Test fun shuffleWithAutoplayPutsAPickAfterEveryFewOfYourSongs() {
        val yours = (1..7).map { "y$it" }
        assertEquals(listOf("y1", "y2", "y3", "p1", "y4", "y5", "y6", "p2", "y7", "p3"),
            Autoplay.interleave(yours, listOf("p1", "p2", "p3")))
        assertEquals(3, Autoplay.smartCount(7))
        assertEquals(0, Autoplay.smartCount(0))
        assertEquals(Autoplay.SMART_MAX, Autoplay.smartCount(1000))
    }

    @Test fun aSeedWithoutRadioFallsBackAndAFailureIsQuiet() = runBlocking {
        val seeds = listOf(song("1", "A"), song("2", "B"))
        val picked = Autoplay.next(seeds, seeds,
            radio = { seed -> if (seed.id == "1") error("offline") else emptyList() },
            fallback = { seed -> listOf(song("${seed.id}-alike", "Fallback ${seed.id}")) })
        assertEquals(listOf("1-alike", "2-alike"), picked.map { it.id })
    }
}
