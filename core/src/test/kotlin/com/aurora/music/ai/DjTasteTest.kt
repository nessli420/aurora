package com.aurora.music.ai

import com.aurora.music.data.PlayEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DjTasteTest {
    private val now = 1_800_000_000_000L
    private val hour = DjTaste.HOUR
    private val day = DjTaste.DAY

    private fun play(id: String, ago: Long, artist: String = "Artist", listened: Long? = null) =
        PlayEvent(id, "Title $id", artist, "Album", "", "", "", 200, now - ago, listened)

    private fun taste(vararg events: PlayEvent) = DjTaste(events.toList(), now, 0)

    @Test fun repeatNeedsRecentReplays() {
        val t = taste(play("a", day), play("a", 2 * day), play("b", day), play("c", 40 * day), play("c", 41 * day))
        assertEquals(setOf("a"), t.repeat.keys)
        assertEquals(2, t.plays["c"])
    }

    @Test fun pastIsWhatYouStoppedPlaying() {
        val t = taste(play("gone", 90 * day), play("gone", 91 * day), play("back", day), play("back", 90 * day), play("back", 91 * day), play("once", 90 * day))
        assertEquals(setOf("gone"), t.past.keys)
    }

    @Test fun hourMatchesTheListeningClock() {
        val t = taste(play("evening", 3 * day), play("evening", 5 * day + hour), play("morning", 3 * day + 12 * hour), play("morning", 5 * day + 12 * hour))
        assertEquals(setOf("evening"), t.hour.keys)
    }

    @Test fun skipsRadioAndJustHeardAreHandled() {
        val t = taste(play("skip", day, listened = 5_000), play("skip", 2 * day, listened = 5_000), play("radio:1", day), play("radio:1", 2 * day),
            play("now", hour), play("full", day, listened = 180_000))
        assertTrue("skip" !in t.plays && "radio:1" !in t.plays)
        assertEquals(setOf("now"), t.justHeard)
        assertTrue("full" in t.plays)
    }

    @Test fun recentArtistsLeadAndFeaturesCollapse() {
        val t = taste(play("a", day, "New Love, Guest"), play("b", 2 * day, "New Love feat. Someone"), play("c", 200 * day, "Old Flame"), play("d", 201 * day, "Old Flame"), play("e", 202 * day, "Old Flame"))
        assertEquals(listOf("New Love", "Old Flame"), t.artists)
    }

    @Test fun sampleFavoursHeavyWeightsWithoutRepeats() {
        val weights = mapOf("heavy" to 200, "light" to 1, "mid" to 3)
        val first = (0 until 200).count { DjTaste.sample(weights, 1, Random(it)).single() == "heavy" }
        assertTrue(first > 180)
        assertEquals(3, DjTaste.sample(weights, 5, Random(1)).toSet().size)
        assertFalse(DjTaste.sample(emptyMap<String, Int>(), 3, Random(1)).isNotEmpty())
    }
}
