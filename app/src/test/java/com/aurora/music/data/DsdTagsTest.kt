package com.aurora.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class DsdTagsTest {
    @Test fun tagsRoundTripWithNullsAndBlanks() {
        val entries = mapOf(
            "content://media/external/file/1" to ("1700000000:123456" to
                DsdTags("Sång ü 音", "Artist", "Album", "", null, 3, null, 2024, 180_000_000L, 2_822_400, 2)),
            "content://media/external/file/2" to ("1700000001:654321" to
                DsdTags(null, null, null, null, "Classical", null, 2, null, 0L, 5_644_800, 6)),
        )
        val bytes = ByteArrayOutputStream().also { writeDsdTags(entries, it) }.toByteArray()
        assertEquals(entries, readDsdTags(ByteArrayInputStream(bytes)))
    }

    @Test fun anotherVersionReadsAsEmpty() {
        assertTrue(readDsdTags(ByteArrayInputStream(byteArrayOf(0, 0, 0, 99, 0, 0, 0, 0))).isEmpty())
    }
}
