package com.aurora.music.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.tanh

// math in float for headroom transport stays 16-bit since exoplayer silence-skip/sonic run after us
// and only accept 16-bit emitting float here would break them hi-res float is a separate no-dsp path
@UnstableApi
class AuroraDspProcessor : BaseAudioProcessor() {

    @Volatile
    var enabled: Boolean = false

    @Volatile
    private var coeffs: Coeffs? = null

    @Volatile
    private var params: DspParams = DspParams()
    private var configuredRate: Int = 0

    // preallocated never reallocated in queueInput for realtime safety
    private val n = DspCoeffBuilder.TOTAL_BIQUADS
    private val xL1 = FloatArray(n); private val xL2 = FloatArray(n)
    private val yL1 = FloatArray(n); private val yL2 = FloatArray(n)
    private val xR1 = FloatArray(n); private val xR2 = FloatArray(n)
    private val yR1 = FloatArray(n); private val yR2 = FloatArray(n)

    private val ringL = FloatArray(DspCoeffBuilder.MAX_CROSSFEED_DELAY + 1)
    private val ringR = FloatArray(DspCoeffBuilder.MAX_CROSSFEED_DELAY + 1)
    private var cfWrite = 0
    private var cfLpfL = 0f
    private var cfLpfR = 0f

    private val dRingL = FloatArray(DspCoeffBuilder.MAX_CHANNEL_DELAY + 1)
    private val dRingR = FloatArray(DspCoeffBuilder.MAX_CHANNEL_DELAY + 1)
    private var dWrite = 0

    private var limGain = 1f
    private var compGain = 1f

    private var smoothCoef = 0f
    private var curPreamp = 1f
    private var curBalL = 1f
    private var curBalR = 1f
    private var curWidth = 1f
    private var smoothInit = false

    // coefficients rebuilt off the audio thread
    fun update(p: DspParams) {
        params = p
        if (configuredRate > 0) coeffs = DspCoeffBuilder.build(p, configuredRate)
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        // stereo 16-bit only anything else bypasses the dsp
        if (inputAudioFormat.channelCount != 2 || inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioFormat.NOT_SET
        }
        configuredRate = inputAudioFormat.sampleRate
        smoothCoef = exp(-1.0 / (0.005 * configuredRate)).toFloat()
        coeffs = DspCoeffBuilder.build(params, configuredRate)
        zeroState()
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val rem = inputBuffer.remaining()
        if (rem == 0) return
        val output = replaceOutputBuffer(rem)
        val c = coeffs

        if (!enabled || c == null) {
            output.put(inputBuffer)
            output.flip()
            return
        }

        inputBuffer.order(ByteOrder.nativeOrder())
        output.order(ByteOrder.nativeOrder())
        val frames = rem / 4
        var p = inputBuffer.position()

        // hoist snapshot into locals no per-sample volatile reads
        val b0 = c.b0; val b1 = c.b1; val b2 = c.b2; val a1 = c.a1; val a2 = c.a2; val nb = c.nBiquads
        val tPreamp = c.preampLin; val tBalL = c.balL; val tBalR = c.balR; val tWidth = c.width
        val satDrive = c.satDrive; val satK = 1f + 5f * satDrive
        val trimL = c.trimL; val trimR = c.trimR
        val delL = c.delayL; val delR = c.delayR; val dSize = dRingL.size; val delayOn = delL > 0 || delR > 0
        val sc = smoothCoef
        val cfAmt = c.crossfeedAmt; val cfA = c.crossfeedLpfA; val cfDelay = c.crossfeedDelay
        val ringSize = ringL.size
        val limOn = c.limiterEnabled; val ceiling = c.ceilingLin; val limAtt = c.limAtt; val limRel = c.limRel
        val compOn = c.compEnabled; val compThr = c.compThreshLin; val compRatio = c.compRatio
        val compAtt = c.compAtt; val compRel = c.compRel

        if (!smoothInit) { curPreamp = tPreamp; curBalL = tBalL; curBalR = tBalR; curWidth = tWidth; smoothInit = true }

        var i = 0
        while (i < frames) {
            var l = inputBuffer.getShort(p) / 32768f
            var r = inputBuffer.getShort(p + 2) / 32768f
            p += 4

            l = cascade(l, b0, b1, b2, a1, a2, xL1, xL2, yL1, yL2, nb)
            r = cascade(r, b0, b1, b2, a1, a2, xR1, xR2, yR1, yR2, nb)

            curPreamp = tPreamp + (curPreamp - tPreamp) * sc
            curBalL = tBalL + (curBalL - tBalL) * sc
            curBalR = tBalR + (curBalR - tBalR) * sc
            curWidth = tWidth + (curWidth - tWidth) * sc

            l *= curPreamp * curBalL * trimL
            r *= curPreamp * curBalR * trimR

            if (satDrive > 0f) {
                val sl = tanh(satK * l) / satK
                val sr = tanh(satK * r) / satK
                l = sl + satDrive * 0.2f * (sl * sl - 0.33f)
                r = sr + satDrive * 0.2f * (sr * sr - 0.33f)
            }

            val mid = 0.5f * (l + r)
            val side = 0.5f * (l - r) * curWidth
            l = mid + side
            r = mid - side

            if (cfAmt > 0f) {
                val readIdx = (cfWrite - cfDelay + ringSize) % ringSize
                val dL = ringL[readIdx]; val dR = ringR[readIdx]
                cfLpfR = cfA * cfLpfR + (1f - cfA) * dR
                cfLpfL = cfA * cfLpfL + (1f - cfA) * dL
                ringL[cfWrite] = l; ringR[cfWrite] = r
                cfWrite = (cfWrite + 1) % ringSize
                l += cfAmt * cfLpfR
                r += cfAmt * cfLpfL
            }

            if (compOn) {
                val level = max(abs(l), abs(r))
                val desired = if (level > compThr) {
                    Math.pow((level / compThr).toDouble(), (1.0 / compRatio - 1.0)).toFloat()
                } else 1f
                val coef = if (desired < compGain) compAtt else compRel
                compGain = desired + (compGain - desired) * coef
                l *= compGain; r *= compGain
            }

            if (limOn) {
                val peak = max(abs(l), abs(r))
                val desired = if (peak > ceiling) ceiling / peak else 1f
                val coef = if (desired < limGain) limAtt else limRel
                limGain = desired + (limGain - desired) * coef
                l *= limGain; r *= limGain
            }

            if (delayOn) {
                dRingL[dWrite] = l; dRingR[dWrite] = r
                l = dRingL[(dWrite - delL + dSize) % dSize]
                r = dRingR[(dWrite - delR + dSize) % dSize]
                dWrite = (dWrite + 1) % dSize
            }

            output.putShort(toPcm16(l))
            output.putShort(toPcm16(r))
            i++
        }
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    private fun toPcm16(x: Float): Short {
        val v = x * 32767f
        val i = when {
            v >= 32767f -> 32767
            v <= -32768f -> -32768
            else -> Math.round(v)
        }
        return i.toShort()
    }

    private fun cascade(
        input: Float,
        b0: FloatArray, b1: FloatArray, b2: FloatArray, a1: FloatArray, a2: FloatArray,
        x1: FloatArray, x2: FloatArray, y1: FloatArray, y2: FloatArray, n: Int,
    ): Float {
        var s = input
        var k = 0
        while (k < n) {
            val x = s
            val y = b0[k] * x + b1[k] * x1[k] + b2[k] * x2[k] - a1[k] * y1[k] - a2[k] * y2[k]
            x2[k] = x1[k]; x1[k] = x; y2[k] = y1[k]; y1[k] = y
            s = y
            k++
        }
        return s
    }

    private fun zeroState() {
        xL1.fill(0f); xL2.fill(0f); yL1.fill(0f); yL2.fill(0f)
        xR1.fill(0f); xR2.fill(0f); yR1.fill(0f); yR2.fill(0f)
        ringL.fill(0f); ringR.fill(0f)
        dRingL.fill(0f); dRingR.fill(0f); dWrite = 0
        cfWrite = 0; cfLpfL = 0f; cfLpfR = 0f
        limGain = 1f; compGain = 1f
        smoothInit = false
    }

    override fun onReset() {
        zeroState()
    }
}
