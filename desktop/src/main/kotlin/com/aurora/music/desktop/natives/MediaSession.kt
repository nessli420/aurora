package com.aurora.music.desktop.natives

interface MediaSession : AutoCloseable {
    interface Callbacks {
        fun onButton(button: SmtcButton) {}
        fun onSeek(positionMs: Long) {}
        fun onShuffle(enabled: Boolean) {}
        fun onRepeat(mode: SmtcRepeat) {}
    }

    fun metadata(title: String?, artist: String?, album: String? = null, albumArtist: String? = null, thumbnail: ByteArray? = null): Boolean
    fun status(status: SmtcStatus): Boolean
    fun timeline(positionMs: Long, durationMs: Long): Boolean
    fun buttons(): Boolean
    fun shuffle(enabled: Boolean): Boolean
    fun repeat(mode: SmtcRepeat): Boolean
}
