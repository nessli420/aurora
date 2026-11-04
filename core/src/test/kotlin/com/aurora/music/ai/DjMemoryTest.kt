package com.aurora.music.ai

import com.aurora.music.model.Song
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

class DjMemoryTest {
    private val day = 86_400_000L
    private var clock = 100 * day
    private val memory = DjMemory(null) { clock }

    private fun song(id: String) = Song(id, "Title $id", "Artist $id", "", "", 200)

    private val stats = DjStats(10, "Big", "Song", 5, artistsThisWeek = 12, streakDays = 4, newArtist = "Fresh", monthArtist = "Month")

    @Test fun placedTracksRestThenReturnAfterUnplayedOnes() {
        val a = song("a"); val b = song("b"); val c = song("c")
        memory.placed(listOf(a))
        assertEquals(listOf(b), memory.freshFirst(listOf(a, b)))
        clock += 4 * day
        memory.placed(listOf(c))
        clock += 4 * day
        assertEquals(listOf(b, a, c), memory.freshFirst(listOf(c, a, b)))
        clock += 20 * day
        assertEquals(listOf(c, a, b), memory.freshFirst(listOf(c, a, b)))
    }

    @Test fun openersAvoidTheLastFew() {
        val recent = listOf(DjSegment.REPEAT, DjSegment.LIKED, DjSegment.PAST)
        recent.forEach(memory::opened)
        repeat(30) { seed ->
            val plan = memory.plan(Random(seed))
            assertFalse(plan.first() in recent)
            assertTrue(DjSegment.MIX !in plan)
            assertEquals(DjSegment.entries.size - 1, plan.size)
        }
    }

    @Test fun firstOpenersDiffer() {
        assertTrue((0 until 30).map { DjMemory(null).plan(Random(it)).first() }.toSet().size > 3)
    }

    @Test fun aStatIsNotRepeatedAndNotAlwaysSpoken() {
        val first = memory.nextFact(stats)
        assertTrue(first != null)
        assertNull(memory.nextFact(stats))
        val said = mutableSetOf(first!!)
        repeat(stats.facts().size - 1) {
            clock += 2 * 3_600_000L
            assertTrue(said.add(memory.nextFact(stats)!!))
        }
        clock += 2 * 3_600_000L
        assertNull(memory.nextFact(stats))
        clock += 4 * day
        assertTrue(memory.nextFact(stats) != null)
        assertNull(memory.nextFact(null))
    }

    @Test fun onlyStatsWithSomethingToSayCount() {
        assertEquals(emptyList<DjFact>(), DjStats(0, null, null, 0).facts())
        assertEquals(listOf(DjFact.TRACK), DjStats(1, "Big", "Song", 3).facts())
        assertTrue(DjFact.MONTH_ARTIST !in DjStats(5, "Big", "Song", 3, monthArtist = "Big").facts())
    }

    @Test fun survivesRestartAndABrokenFile() = runBlocking {
        val dir = Files.createTempDirectory("dj-memory").toFile()
        try {
            val file = File(dir, "memory.json")
            val first = DjMemory(file) { clock }
            first.placed(listOf(song("a")))
            first.opened(DjSegment.REPEAT)
            val turn = first.nextTurn()
            withTimeout(5_000) { while (!file.exists() || !file.readText().contains("REPEAT")) delay(10) }
            delay(50)
            val second = DjMemory(file) { clock }
            assertEquals(emptyList<Song>(), second.freshFirst(listOf(song("a"))))
            assertEquals(turn + 1, second.nextTurn())
            second.clear()
            assertEquals(listOf(song("a")), second.freshFirst(listOf(song("a"))))
            delay(100)
            file.writeText("{not json")
            assertEquals(1, DjMemory(file) { clock }.nextTurn())
        } finally { dir.deleteRecursively() }
    }
}
