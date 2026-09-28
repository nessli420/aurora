package com.aurora.music.desktop.natives

interface SmtcListener {
    fun onButton(button: Int)
    fun onSeek(positionMs: Long)
    fun onShuffle(enabled: Boolean)
    fun onRepeat(mode: Int)
}

object SmtcNative {
    init { NativeLoader.load("aurora_native") }

    external fun create(hwnd: Long, listener: SmtcListener): Long
    external fun metadata(handle: Long, title: String?, artist: String?, album: String?, albumArtist: String?, thumbnail: ByteArray?): Int
    external fun playbackStatus(handle: Long, status: Int): Int
    external fun timeline(handle: Long, positionMs: Long, durationMs: Long, minSeekMs: Long, maxSeekMs: Long): Int
    external fun buttons(handle: Long, mask: Int): Int
    external fun shuffle(handle: Long, enabled: Boolean): Int
    external fun repeat(handle: Long, mode: Int): Int
    external fun destroy(handle: Long)
}
