package com.aurora.music.data

/**
 * JNI bridge to the vendored Chromaprint library (4.4c). Decodes a file to 16-bit PCM via
 * [AudioDecoder] and produces an AcoustID-compatible compressed fingerprint. Fingerprinting only the
 * first [MAX_SECONDS] (as fpcalc does) bounds the work; the *full* track duration is sent to AcoustID
 * separately for matching.
 *
 * [available] is false when the native library isn't present, so callers degrade gracefully.
 */
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

    /** Compressed base64 fingerprint of [path], or null if unavailable / undecodable. */
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
            // Stop once we've fed MAX_SECONDS of audio — enough for a stable fingerprint.
            isCancelled = { ctx != 0L && sampleRate > 0 && frames >= MAX_SECONDS.toLong() * sampleRate },
        )
        if (ctx == 0L) return null
        if (!ok && frames == 0L) { runCatching { nativeFinish(ctx) }; return null }
        return runCatching { nativeFinish(ctx) }.getOrNull()
    }
}
