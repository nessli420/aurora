package com.aurora.music.playback

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Process
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.aurora.music.data.MusicHapticsPrefs
import com.aurora.music.playback.haptics.HapticClock
import com.aurora.music.playback.haptics.HapticKind
import com.aurora.music.playback.haptics.HapticPcmRing
import com.aurora.music.playback.haptics.HapticTimeline
import com.aurora.music.playback.haptics.MusicHapticsAnalyzer
import java.nio.ByteBuffer
import java.util.concurrent.locks.LockSupport

// drum hits found in the decoded audio play on the vibration motor at the moment the speaker sounds them
class MusicHaptics(context: Context) : MusicHapticsAnalyzer.Sink, AutoCloseable {
    // lazy so a disabled feature skips the vibrator queries and the 256 KB ring
    private val output by lazy { vibratorOf(context)?.let(::HapticOutput) }
    private val ringHolder = lazy { HapticPcmRing() }
    private val ring by ringHolder
    private val clock = HapticClock()
    private val timeline = HapticTimeline()
    private val analyzer = MusicHapticsAnalyzer(this)
    private val lock = Any()
    @Volatile private var prefs = MusicHapticsPrefs()
    @Volatile private var engaged = false
    @Volatile private var worker: Worker? = null
    private var previous: Thread? = null

    fun setPrefs(value: MusicHapticsPrefs) {
        prefs = value
        refresh()
    }

    fun setEngaged(value: Boolean) {
        if (engaged == value) return
        engaged = value
        refresh()
    }

    fun pcm(buffer: ByteBuffer, encoding: Int, channels: Int, sampleRate: Int, start: Int, end: Int, timeUs: Long) {
        if (worker != null) ring.push(buffer, encoding, channels, sampleRate, start, end, timeUs)
    }

    fun position(positionUs: Long, speed: Float) {
        if (worker != null) clock.update(positionUs, System.nanoTime(), speed)
    }

    fun playing(value: Boolean) = clock.playing(value, System.nanoTime())

    fun flush() {
        if (ringHolder.isInitialized()) ring.flush()
        clock.reset()
    }

    override fun close() {
        engaged = false
        refresh()
    }

    override fun onTap(timeUs: Long, kind: HapticKind, strength: Float) = timeline.addTap(timeUs, kind, strength, prefs.detail)

    override fun onTexture(timeUs: Long, level: Float) = timeline.addTexture(timeUs, level)

    private fun refresh() = synchronized(lock) {
        val wanted = prefs.enabled && engaged && output != null
        val running = worker
        if (wanted == (running != null)) return
        if (wanted) {
            // the analyser and timeline belong to one thread at a time
            previous?.join(STOP_WAIT_MS)
            clock.reset()
            // allocate here, not on the playback thread
            ringHolder.value
            worker = Worker().also { it.thread.start() }
        } else {
            worker = null
            running?.stop()
            previous = running?.thread
        }
    }

    private inner class Worker {
        @Volatile var running = true
        val thread = Thread({ run(this) }, "aurora-music-haptics").apply { isDaemon = true }

        fun stop() {
            running = false
            LockSupport.unpark(thread)
        }
    }

    private fun run(owner: Worker) {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
        val out = output ?: return
        val cursor = HapticPcmRing.Cursor()
        val scratch = FloatArray(2_048)
        val levels = FloatArray(TEXTURE_SEGMENTS)
        var textureAt = 0L
        var buzzing = false
        val restart = {
            analyzer.reset()
            timeline.clear()
        }
        val feed = analyzer::feed
        restart()
        while (owner.running) {
            ring.drain(cursor, scratch, restart, feed)
            val now = System.nanoTime()
            val position = clock.audibleUs(now)
            if (position == null) {
                if (buzzing) out.stop()
                buzzing = false
                LockSupport.parkNanos(IDLE_NANOS)
                continue
            }
            val settings = prefs
            val leadUs = LEAD_US - settings.offsetMs * 1_000L
            timeline.due(position, leadUs) { tap ->
                val ms = out.tap(tap.kind, tap.strength * settings.intensity)
                buzzing = true
                textureAt = maxOf(textureAt, now + (ms + 4) * 1_000_000L)
            }
            if (settings.texture && out.canTexture && now >= textureAt) {
                // the analysis runs ahead of the speaker, so the coming window's bass is already known
                val from = position + leadUs
                val nextTap = timeline.nextDueUs(leadUs)
                var loudest = 0f
                for (k in levels.indices) {
                    val at = from + k * TEXTURE_SEGMENT_US
                    levels[k] = if (nextTap != null && at >= nextTap + leadUs) 0f
                        else timeline.textureAt(at + TEXTURE_SEGMENT_US / 2) * TEXTURE_GAIN * settings.intensity
                    loudest = maxOf(loudest, levels[k])
                }
                if (loudest > TEXTURE_MIN) {
                    out.texture(levels, TEXTURE_SEGMENT_US / 1_000)
                    buzzing = true
                }
                textureAt = now + TEXTURE_EVERY_NANOS
            }
            val untilTap = timeline.nextDueUs(leadUs)?.let { (it - position) * 1_000L } ?: Long.MAX_VALUE
            val untilTexture = if (settings.texture && out.canTexture) textureAt - now else Long.MAX_VALUE
            LockSupport.parkNanos(minOf(untilTap, untilTexture, BUSY_NANOS).coerceAtLeast(MIN_PARK_NANOS))
        }
        out.stop()
    }

    private class HapticOutput(private val vibrator: Vibrator) {
        val canTexture = vibrator.hasAmplitudeControl()
        private val composed = Build.VERSION.SDK_INT >= 30 &&
            vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_TICK)
        private val kick = if (composed && Build.VERSION.SDK_INT >= 31 &&
            vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_THUD) &&
            vibrator.getPrimitiveDurations(VibrationEffect.Composition.PRIMITIVE_THUD).first() in 1..MAX_KICK_MS) {
            VibrationEffect.Composition.PRIMITIVE_THUD
        } else {
            VibrationEffect.Composition.PRIMITIVE_CLICK
        }
        // many phones have no primitives but ship tuned click, tick and heavy click waveforms
        private val predefined = !composed && Build.VERSION.SDK_INT >= 30 &&
            vibrator.areAllEffectsSupported(VibrationEffect.EFFECT_CLICK, VibrationEffect.EFFECT_TICK) == Vibrator.VIBRATION_EFFECT_SUPPORT_YES
        private val heavy = predefined && vibrator.areAllEffectsSupported(VibrationEffect.EFFECT_HEAVY_CLICK) == Vibrator.VIBRATION_EFFECT_SUPPORT_YES
        private val durations: Map<Int, Long> = if (composed && Build.VERSION.SDK_INT >= 31) {
            val primitives = intArrayOf(kick, VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_TICK)
            primitives.zip(vibrator.getPrimitiveDurations(*primitives).toList()) { primitive, ms -> primitive to ms.toLong().coerceAtLeast(1) }.toMap()
        } else emptyMap()
        private val attributes: Any = if (Build.VERSION.SDK_INT >= 33) {
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA)
        } else {
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        }

        // returns how long the tap lasts
        fun tap(kind: HapticKind, strength: Float): Long {
            val s = strength.coerceIn(0f, 1f)
            return when {
                composed -> {
                    val primitive = when (kind) {
                        HapticKind.KICK -> kick
                        HapticKind.SNARE -> VibrationEffect.Composition.PRIMITIVE_CLICK
                        HapticKind.HAT -> VibrationEffect.Composition.PRIMITIVE_TICK
                    }
                    val scale = when (kind) {
                        HapticKind.KICK -> 0.35f + 0.65f * s
                        HapticKind.SNARE -> 0.25f + 0.5f * s
                        HapticKind.HAT -> 0.15f + 0.3f * s
                    }
                    play(VibrationEffect.startComposition().addPrimitive(primitive, scale.coerceIn(0f, 1f)).compose())
                    durations[primitive] ?: 12L
                }
                predefined -> {
                    val effect = when (kind) {
                        HapticKind.KICK -> if (s >= STRONG && heavy) VibrationEffect.EFFECT_HEAVY_CLICK else VibrationEffect.EFFECT_CLICK
                        HapticKind.SNARE -> if (s >= STRONG) VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_TICK
                        HapticKind.HAT -> VibrationEffect.EFFECT_TICK
                    }
                    play(VibrationEffect.createPredefined(effect))
                    PREDEFINED_MS
                }
                canTexture -> {
                    val (ms, amplitude) = when (kind) {
                        HapticKind.KICK -> 28L to 70 + 185 * s
                        HapticKind.SNARE -> 16L to 50 + 150 * s
                        HapticKind.HAT -> 8L to 30 + 90 * s
                    }
                    play(VibrationEffect.createOneShot(ms, amplitude.toInt().coerceIn(1, 255)))
                    ms
                }
                else -> {
                    val ms = when (kind) {
                        HapticKind.KICK -> 18 + 14 * s
                        HapticKind.SNARE -> 10 + 8 * s
                        HapticKind.HAT -> 6 + 4 * s
                    }.toLong()
                    play(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
                    ms
                }
            }
        }

        fun texture(levels: FloatArray, segmentMs: Long) {
            val amplitudes = IntArray(levels.size) { (levels[it] * 255).toInt().coerceIn(0, 255) }
            play(VibrationEffect.createWaveform(LongArray(levels.size) { segmentMs }, amplitudes, -1))
        }

        fun stop() = runCatching { vibrator.cancel() }

        private fun play(effect: VibrationEffect) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 33) vibrator.vibrate(effect, attributes as VibrationAttributes)
                else @Suppress("DEPRECATION") vibrator.vibrate(effect, attributes as AudioAttributes)
            }
        }
    }

    data class Support(val available: Boolean, val texture: Boolean)

    companion object {
        fun support(context: Context): Support = vibratorOf(context).let { Support(it != null, it?.hasAmplitudeControl() == true) }

        private fun vibratorOf(context: Context): Vibrator? = runCatching {
            if (Build.VERSION.SDK_INT >= 31) (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }.getOrNull()?.takeIf { it.hasVibrator() }

        // the motor takes a moment to spin up after the call
        private const val LEAD_US = 25_000L
        private const val TEXTURE_SEGMENTS = 6
        private const val TEXTURE_SEGMENT_US = 20_000L
        private const val TEXTURE_EVERY_NANOS = 100_000_000L
        private const val TEXTURE_GAIN = 0.4f
        private const val TEXTURE_MIN = 0.03f
        private const val IDLE_NANOS = 20_000_000L
        private const val BUSY_NANOS = 8_000_000L
        private const val MIN_PARK_NANOS = 500_000L
        private const val MAX_KICK_MS = 50
        private const val STOP_WAIT_MS = 200L
        private const val STRONG = 0.55f
        private const val PREDEFINED_MS = 15L
    }
}
