package com.aurora.music.desktop.audio

import com.aurora.music.model.Song
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class PlaybackQueueTest {
    private fun song(id: String) = Song(id, id, "Artist", "Album", "", 60, streamUrl = "file:///$id.flac")
    private fun queue(vararg ids: String, start: Int = 0) = PlaybackQueue(Random(3)).apply { set(ids.map(::song), start) }
    private fun PlaybackQueue.ids() = entries.map { it.song.id }

    @Test fun nextAndPreviousFollowMedia3RepeatSemantics() {
        val queue = queue("a", "b", "c", start = 2)
        assertEquals(-1, queue.next(auto = true))
        assertEquals(1, queue.previous())
        queue.repeat = RepeatMode.ALL
        assertEquals(0, queue.next(auto = true))
        assertEquals(2, queue.previous(0))
        queue.repeat = RepeatMode.ONE
        assertEquals(2, queue.next(auto = true))
        assertEquals(-1, queue.next(auto = false))
        assertEquals(-1, queue.previous(0))
    }

    @Test fun editsKeepTheCurrentEntry() {
        val queue = queue("a", "b", "c", "d", start = 2)
        queue.insert(0, listOf(song("x")))
        assertEquals("c", queue.current?.song?.id)
        queue.move(3, 0)
        assertEquals(listOf("c", "x", "a", "b", "d"), queue.ids())
        assertEquals(0, queue.index)
        queue.move(1, 4)
        assertEquals(0, queue.index)
        queue.remove(1, 3)
        assertEquals(listOf("c", "d", "x"), queue.ids())
        assertEquals("c", queue.current?.song?.id)
        queue.append(listOf(song("y")))
        assertEquals(listOf("c", "d", "x", "y"), queue.ids())
    }

    @Test fun removingTheCurrentEntryMovesToTheNextOrEnds() {
        val queue = queue("a", "b", "c", start = 1)
        queue.remove(1)
        assertEquals("c", queue.current?.song?.id)
        assertFalse(queue.removedPastEnd)
        queue.remove(1)
        assertEquals("a", queue.current?.song?.id)
        assertTrue(queue.removedPastEnd)
        queue.repeat = RepeatMode.ALL
        queue.append(listOf(song("z")))
        queue.select(1)
        queue.remove(1)
        assertEquals(0, queue.index)
        assertFalse(queue.removedPastEnd)
    }

    @Test fun shuffleMovesTheCurrentFirstAndRestoresTheOriginalOrder() {
        val queue = queue("a", "b", "c", "d", "e", start = 3)
        queue.setShuffle(ShuffleTarget.ON)
        assertTrue(queue.shuffle)
        assertEquals(0, queue.index)
        assertEquals("d", queue.current?.song?.id)
        assertEquals(setOf("a", "b", "c", "d", "e"), queue.ids().toSet())
        assertEquals(listOf("a", "b", "c", "d", "e"), queue.restoreIds)
        queue.append(listOf(song("f")))
        queue.remove(queue.ids().indexOf("b"))
        queue.setShuffle(ShuffleTarget.TOGGLE)
        assertFalse(queue.shuffle)
        assertNull(queue.restoreIds)
        assertEquals(listOf("a", "c", "d", "e", "f"), queue.ids())
        assertEquals("d", queue.current?.song?.id)
    }

    @Test fun providedOrderIsAdoptedWithoutReorderingAndHandlesDuplicates() {
        val queue = queue("c", "a", "b", "a")
        queue.setShuffle(ShuffleTarget.ON, listOf("a", "a", "b", "c"))
        assertEquals(listOf("c", "a", "b", "a"), queue.ids())
        queue.setShuffle(ShuffleTarget.OFF)
        assertEquals(listOf("a", "a", "b", "c"), queue.ids())
        assertEquals(listOf(2L, 4L, 3L, 1L), queue.entries.map { it.uid })
    }

    @Test fun replacingKeepsTheEntryOnlyForTheSameStream() {
        val queue = queue("a", "b")
        val uid = queue[0].uid
        queue.replace(0, song("a").copy(title = "Renamed"))
        assertEquals(uid, queue[0].uid)
        assertEquals("Renamed", queue[0].song.title)
        queue.replace(0, song("other"))
        assertNotEquals(uid, queue[0].uid)
    }

    @Test fun newQueueForgetsAStaleRestoreOrder() {
        val queue = queue("a", "b", "c")
        queue.setShuffle(ShuffleTarget.ON)
        queue.set(listOf(song("x"), song("y")), 1)
        assertEquals(listOf("x", "y"), queue.restoreIds)
        queue.setShuffle(ShuffleTarget.OFF)
        assertEquals(listOf("x", "y"), queue.ids())
        assertEquals(1, queue.index)
    }
}
