package com.aurora.music.desktop.natives

enum class SmtcButton { PLAY, PAUSE, STOP, RECORD, FAST_FORWARD, REWIND, NEXT, PREVIOUS, CHANNEL_UP, CHANNEL_DOWN }

enum class SmtcStatus { CLOSED, CHANGING, STOPPED, PLAYING, PAUSED }

enum class SmtcRepeat { NONE, TRACK, LIST }

class SmtcSession private constructor(private val handle: Long) : AutoCloseable {
    interface Callbacks {
        fun onButton(button: SmtcButton) {}
        fun onSeek(positionMs: Long) {}
        fun onShuffle(enabled: Boolean) {}
        fun onRepeat(mode: SmtcRepeat) {}
    }

    @Volatile
    private var closed = false

    fun metadata(title: String?, artist: String?, album: String? = null, albumArtist: String? = null, thumbnail: ByteArray? = null) =
        update { SmtcNative.metadata(handle, title, artist, album, albumArtist, thumbnail) }

    fun status(status: SmtcStatus) = update { SmtcNative.playbackStatus(handle, status.ordinal) }

    fun timeline(positionMs: Long, durationMs: Long, minSeekMs: Long = 0, maxSeekMs: Long = durationMs) =
        update { SmtcNative.timeline(handle, positionMs, durationMs, minSeekMs, maxSeekMs) }

    fun buttons(play: Boolean = true, pause: Boolean = true, next: Boolean = true, previous: Boolean = true, stop: Boolean = false) =
        update {
            val mask = listOf(play, pause, next, previous, stop).foldIndexed(0) { bit, mask, on -> if (on) mask or (1 shl bit) else mask }
            SmtcNative.buttons(handle, mask)
        }

    fun shuffle(enabled: Boolean) = update { SmtcNative.shuffle(handle, enabled) }

    fun repeat(mode: SmtcRepeat) = update { SmtcNative.repeat(handle, mode.ordinal) }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        SmtcNative.destroy(handle)
    }

    private inline fun update(call: () -> Int): Boolean = !closed && call() == 0

    companion object {
        fun create(hwnd: Long, callbacks: Callbacks): SmtcSession {
            val handle = SmtcNative.create(hwnd, object : SmtcListener {
                override fun onButton(button: Int) { SmtcButton.entries.getOrNull(button)?.let(callbacks::onButton) }
                override fun onSeek(positionMs: Long) = callbacks.onSeek(positionMs)
                override fun onShuffle(enabled: Boolean) = callbacks.onShuffle(enabled)
                override fun onRepeat(mode: Int) { SmtcRepeat.entries.getOrNull(mode)?.let(callbacks::onRepeat) }
            })
            check(handle > 0) { "SMTC unavailable: 0x%08X".format(handle.toInt()) }
            return SmtcSession(handle)
        }
    }
}
