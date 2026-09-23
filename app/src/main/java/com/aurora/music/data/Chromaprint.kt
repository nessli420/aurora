package com.aurora.music.data

// fingerprint only first MAX_SECONDS like fpcalc full duration sent to acoustid separately
object Chromaprint {
    @Volatile private var loaded = false

    init {
        loaded = runCatching { System.loadLibrary("aurora_fp"); true }.getOrDefault(false)
    }

    val available: Boolean get() = loaded

    private const val MAX_SECONDS = 120

    private external fun nativeNew(sampleRate: Int, channels: Int): Long
    private external fun nativeFeed(ctx: Long, pcm: ShortArray, length: Int)
    private external fun nativeFinish(ctx: Long): String?
    private external fun nativeFinishRaw(ctx: Long): IntArray?

    data class RawFingerprint(val itemDurationMs: Int, val delayMs: Int, val values: IntArray)

    fun rawFingerprint(path: String, seconds: Int, context: android.content.Context,
        cancelled: () -> Boolean = { false }): RawFingerprint? {
        if (!loaded) return null
        var ctx = 0L
        var formatSeen = false
        var sampleRate = 0
        var channels = 1
        var frames = 0L
        AudioDecoder.decode(path, { sr, ch ->
            formatSeen = true
            sampleRate = sr
            channels = ch.coerceAtLeast(1)
            ctx = runCatching { nativeNew(sr, channels) }.getOrDefault(0L)
        }, { pcm, len ->
            if (ctx != 0L) {
                nativeFeed(ctx, pcm, len)
                frames += len / channels
            }
        }, { cancelled() || formatSeen && (ctx == 0L || frames >= seconds.toLong() * sampleRate) }, context)
        if (ctx == 0L) return null
        val raw = runCatching { nativeFinishRaw(ctx) }.getOrNull() ?: return null
        if (raw.size < 3 || raw[0] <= 0) return null
        return RawFingerprint(raw[0], raw[1], raw.copyOfRange(2, raw.size))
    }

    fun fingerprint(path: String): String? {
        if (!loaded) return null
        var ctx = 0L
        var sampleRate = 0
        var channels = 1
        var frames = 0L
        val ok = AudioDecoder.decode(
            path,
            onFormat = { sr, ch ->
                sampleRate = sr; channels = ch.coerceAtLeast(1)
                ctx = runCatching { nativeNew(sr, channels) }.getOrDefault(0L)
            },
            onPcm = { pcm, len ->
                if (ctx != 0L) {
                    runCatching { nativeFeed(ctx, pcm, len) }
                    frames += len / channels
                }
            },
            isCancelled = { ctx != 0L && sampleRate > 0 && frames >= MAX_SECONDS.toLong() * sampleRate },
        )
        if (ctx == 0L) return null
        if (!ok && frames == 0L) { runCatching { nativeFinish(ctx) }; return null }
        return runCatching { nativeFinish(ctx) }.getOrNull()
    }
}
