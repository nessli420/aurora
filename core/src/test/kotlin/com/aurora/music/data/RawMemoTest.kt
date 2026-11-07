package com.aurora.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RawMemoTest {
    @Test fun decodesOncePerRawChangeAndCachesNull() {
        var decodes = 0
        val memo = RawMemo { raw -> decodes++; raw?.takeIf { it != "corrupt" }?.length }
        assertNull(memo("corrupt"))
        assertNull(memo("corrupt"))
        assertEquals(1, decodes)
        assertEquals(3, memo("abc"))
        assertEquals(3, memo("abc"))
        assertEquals(2, decodes)
        assertNull(memo(null))
        assertNull(memo(null))
        assertEquals(3, decodes)
    }

    @Test fun aFailedResultStillThrowsFromTheCache() {
        var decodes = 0
        val failure = IllegalStateException("bad")
        val memo = RawMemo<Result<Int>> { decodes++; Result.failure(failure) }
        assertSame(failure, memo("x").exceptionOrNull())
        assertSame(failure, memo("x").exceptionOrNull())
        assertEquals(1, decodes)
    }
}
