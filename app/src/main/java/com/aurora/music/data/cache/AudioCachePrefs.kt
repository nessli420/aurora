package com.aurora.music.data.cache

data class AudioCachePrefs(val enabled: Boolean = true, val limitMb: Int = 1024) {
    val limitBytes: Long get() = limitMb.coerceIn(256, 5120) * 1024L * 1024L
    companion object { val limitsMb = listOf(256, 512, 1024, 2048, 5120) }
}
