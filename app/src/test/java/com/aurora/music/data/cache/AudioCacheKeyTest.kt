package com.aurora.music.data.cache

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioCacheKeyTest {
    // keys from the old per-byte %02x hashing; cached files on disk are found by them
    @Test fun keysMatchTheOnesAlreadyOnDisk() {
        assertEquals("85fd9ed3661d88d082dffce20907e4d50ce1f0161c0c0daf3b33eeab47af1553",
            AudioCache.keyFor("https://music.example/rest/stream?id=café&f=flac", "provider:42"))
        assertEquals("5bd277bf1ddecdf1f7cda3cc9032fabd2a06063a3a6c78c87b0eb36eb0e38937",
            AudioCache.keyFor("https://a.example/x.mp3", ""))
    }
}
