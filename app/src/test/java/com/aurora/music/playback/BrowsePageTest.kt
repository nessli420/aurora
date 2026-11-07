package com.aurora.music.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowsePageTest {
    private val items = List(1_200) { it }

    @Test fun unpagedRequestIsCappedAndSecondPageDoesNotOverflow() {
        assertEquals(items.take(500), items.browsePage(0, Int.MAX_VALUE))
        assertEquals(items.subList(500, 1_000), items.browsePage(1, Int.MAX_VALUE))
        assertEquals(emptyList<Int>(), items.browsePage(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test fun oversizedPagesDoNotSkipItems() {
        assertEquals(items.subList(500, 1_000), items.browsePage(1, 1_000))
        assertEquals(items.subList(1_000, 1_200), items.browsePage(2, 1_000))
    }

    @Test fun pagesSliceInOrder() {
        assertEquals((20 until 30).toList(), items.browsePage(2, 10))
        assertEquals(items.subList(1_000, 1_200), items.browsePage(2, 500))
        assertEquals(emptyList<Int>(), items.browsePage(3, 500))
    }
}
