package com.aurora.music.desktop.audio

import com.aurora.music.R
import com.aurora.music.desktop.natives.AlsaErrors
import com.aurora.music.desktop.natives.AlsaException
import com.aurora.music.desktop.natives.AlsaNative
import com.aurora.music.desktop.natives.AlsaOutput
import com.aurora.music.desktop.natives.AudioDevice
import com.aurora.music.desktop.natives.DeviceEvent
import com.aurora.music.desktop.natives.DeviceKind
import com.aurora.music.desktop.natives.OutputEncoding
import com.aurora.music.localization.appString
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class HardwareDevice(val id: String, val card: String, val name: String, val driver: String, val plug: String)

class HardwareProbe(val error: Int, val masks: IntArray)

interface ExclusiveHardware {
    fun devices(): List<HardwareDevice>
    fun capabilities(deviceId: String, rates: IntArray): HardwareProbe
    fun open(deviceId: String, sampleRate: Int, encoding: OutputEncoding, bufferMs: Int, rates: IntArray): HardwareOutput
}

interface HardwareOutput : AudioOutput {
    fun capabilities(): IntArray?
}

sealed interface ExclusiveFormats {
    data class Supported(val rates: List<Int>, val encodings: Set<OutputEncoding>) : ExclusiveFormats
    data object Busy : ExclusiveFormats
    data object NoDevice : ExclusiveFormats
    data object Unknown : ExclusiveFormats
}

class LinuxOutputBackend(
    private val shared: OutputBackend = JavaSoundBackend,
    private val hardware: ExclusiveHardware? = AlsaHardware.load(),
    private val server: String = soundServer(),
    private val busyWaitMs: Long = 7_000,
    private val sleep: (Long) -> Unit = Thread::sleep,
) : OutputBackend {
    private val masks = ConcurrentHashMap<String, MutableMap<Int, Int>>()
    @Volatile private var hardwareDevices: List<HardwareDevice> = emptyList()
    private val watcher = DeviceWatcher(::devices)

    override val exclusiveAvailable: Boolean get() = hardware != null

    override fun devices(): List<AudioDevice> {
        val sharedDevices = shared.devices()
        val hardware = hardware ?: return sharedDevices
        val found = runCatching { hardware.devices() }.getOrDefault(emptyList())
        hardwareDevices = found
        masks.keys.retainAll(found.map { it.id }.toSet())
        val perCard = found.groupingBy { it.card }.eachCount()
        val default = sharedDevices.firstOrNull { it.isDefault }?.copy(name = server)
        return listOfNotNull(default) + found.map { device ->
            val name = if ((perCard[device.card] ?: 0) > 1) "${device.card} · ${device.name}" else device.card
            AudioDevice(device.id, name, kindOf(device), false)
        }
    }

    override fun mixRate(deviceId: String?): Int? = null

    override fun supportsExclusive(deviceId: String?, sampleRate: Int, encoding: OutputEncoding): Boolean {
        if (hardware == null || deviceId == null || !isHardware(deviceId) || encoding == OutputEncoding.F32) return false
        val mask = mask(deviceId, sampleRate) ?: return sampleRate in RATES
        return mask and (1 shl encoding.ordinal) != 0
    }

    override fun exclusiveUnavailableReason(deviceId: String?): String? {
        if (hardware == null) return null
        if (deviceId == null || !isHardware(deviceId)) return appString(R.string.text_choose_an_output_device_to_use_exclusive_mode_b2a25d)
        return null
    }

    override fun open(deviceId: String?, exclusive: Boolean, sampleRate: Int, encoding: OutputEncoding, bufferMs: Int): AudioOutput {
        if (!exclusive) return openShared(deviceId, sampleRate, encoding, bufferMs)
        val hardware = checkNotNull(hardware) { appString(R.string.text_exclusive_mode_is_unavailable_on_this_device_e51016) }
        require(deviceId != null && isHardware(deviceId)) { appString(R.string.text_choose_an_output_device_to_use_exclusive_mode_b2a25d) }
        val rates = (RATES + sampleRate).distinct().toIntArray()
        var waited = 0L
        while (true) {
            try {
                val output = hardware.open(deviceId, sampleRate, encoding, bufferMs, rates)
                output.capabilities()?.let { remember(deviceId, rates, it) }
                return output
            } catch (e: AlsaException) {
                if (e.error == AlsaErrors.EINVAL && masks[deviceId]?.get(sampleRate) == null) {
                    val supported = mask(deviceId, sampleRate) ?: throw e
                    val fallback = OutputNegotiator.PREFERENCE.firstOrNull { supported and (1 shl it.ordinal) != 0 } ?: throw e
                    if (fallback == encoding) throw e
                    return hardware.open(deviceId, sampleRate, fallback, bufferMs, rates).also { it.capabilities()?.let { found -> remember(deviceId, rates, found) } }
                }
                if (e.error != AlsaErrors.EBUSY) throw e
                if (waited >= busyWaitMs) {
                    throw AlsaException(e.error, appString(R.string.text_the_device_is_in_use_by_the_sound_server_or_another_app_ask_pipew_e1e816))
                }
                sleep(BUSY_RETRY_MS)
                waited += BUSY_RETRY_MS
            }
        }
    }

    override fun listen(listener: (DeviceEvent) -> Unit): AutoCloseable = watcher.listen(listener)

    override fun keepAwake(enabled: Boolean) = shared.keepAwake(enabled)

    fun formats(deviceId: String?): ExclusiveFormats {
        if (deviceId == null || !isHardware(deviceId)) return ExclusiveFormats.NoDevice
        val table = masks[deviceId]?.filterKeys { it in RATES }?.takeIf { it.size == RATES.size } ?: run {
            val probe = runCatching { hardware?.capabilities(deviceId, RATES.toIntArray()) }.getOrNull()
            when {
                probe == null -> return ExclusiveFormats.Unknown
                probe.error == AlsaErrors.EBUSY -> return ExclusiveFormats.Busy
                probe.error < 0 -> return ExclusiveFormats.Unknown
                else -> remember(deviceId, RATES.toIntArray(), probe.masks)
            }
        }
        val rates = RATES.filter { (table[it] ?: 0) != 0 }
        val encodings = OutputEncoding.entries.filter { encoding -> rates.any { table.getValue(it) and (1 shl encoding.ordinal) != 0 } }.toSet()
        return if (rates.isEmpty()) ExclusiveFormats.Unknown else ExclusiveFormats.Supported(rates, encodings)
    }

    private fun mask(deviceId: String, sampleRate: Int): Int? {
        masks[deviceId]?.get(sampleRate)?.let { return it }
        val rates = (RATES + sampleRate).distinct().toIntArray()
        val probe = runCatching { hardware?.capabilities(deviceId, rates) }.getOrNull() ?: return 0
        if (probe.error == AlsaErrors.EBUSY) return null
        if (probe.error < 0) return 0
        return remember(deviceId, rates, probe.masks)[sampleRate] ?: 0
    }

    private fun remember(deviceId: String, rates: IntArray, found: IntArray): Map<Int, Int> {
        val table = masks.getOrPut(deviceId) { ConcurrentHashMap() }
        rates.forEachIndexed { index, rate -> found.getOrNull(index)?.let { table[rate] = it } }
        return table
    }

    private fun openShared(deviceId: String?, sampleRate: Int, encoding: OutputEncoding, bufferMs: Int): AudioOutput {
        val plug = hardwareDevices.firstOrNull { it.id == deviceId }?.plug
        val target = when {
            plug == null -> deviceId
            server != DIRECT -> null
            else -> shared.devices().firstOrNull { "[$plug]" in it.id }?.id
        }
        return try {
            shared.open(target, false, sampleRate, encoding, bufferMs)
        } catch (e: Exception) {
            if (target == null) throw e
            shared.open(null, false, sampleRate, encoding, bufferMs)
        }
    }

    private fun isHardware(deviceId: String) = deviceId.startsWith("hw:")

    companion object {
        const val DIRECT = "ALSA"
        private const val BUSY_RETRY_MS = 250L
        val RATES = listOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000, 352_800, 384_000)

        val instance: LinuxOutputBackend by lazy { LinuxOutputBackend() }

        fun soundServer(runtimeDir: String? = System.getenv("XDG_RUNTIME_DIR")): String {
            val dir = runtimeDir?.takeIf(String::isNotBlank)?.let(::File) ?: return DIRECT
            return when {
                File(dir, "pipewire-0").exists() -> "PipeWire"
                File(dir, "pulse/native").exists() -> "PulseAudio"
                else -> DIRECT
            }
        }

        fun kindOf(device: HardwareDevice): DeviceKind {
            val name = "${device.card} ${device.name}".lowercase(Locale.ROOT)
            return when {
                "hdmi" in name || "displayport" in name -> DeviceKind.DIGITAL_DISPLAY
                "iec958" in name || "s/pdif" in name || "spdif" in name -> DeviceKind.SPDIF
                "headphone" in name -> DeviceKind.HEADPHONES
                device.driver.equals("USB-Audio", ignoreCase = true) -> DeviceKind.UNKNOWN
                else -> DeviceKind.SPEAKERS
            }
        }
    }
}

object AlsaHardware : ExclusiveHardware {
    fun load(): ExclusiveHardware? = this.takeIf { AlsaNative.available }

    override fun devices(): List<HardwareDevice> =
        AlsaNative.devices()?.asList()?.chunked(5) { (id, card, name, driver, plug) -> HardwareDevice(id, card, name, driver, plug) }.orEmpty()

    override fun capabilities(deviceId: String, rates: IntArray): HardwareProbe {
        val result = AlsaNative.capabilities(deviceId, rates, 2) ?: return HardwareProbe(AlsaErrors.EINVAL, IntArray(0))
        return HardwareProbe(result[0], result.copyOfRange(1, result.size))
    }

    override fun open(deviceId: String, sampleRate: Int, encoding: OutputEncoding, bufferMs: Int, rates: IntArray): HardwareOutput =
        AlsaAudioOutput(AlsaOutput.open(deviceId, sampleRate, encoding, bufferMs, rates))
}

class AlsaAudioOutput(private val output: AlsaOutput) : HardwareOutput {
    override val id: Long get() = output.id
    override val deviceId: String get() = output.deviceId
    override val exclusive: Boolean get() = true
    override val sampleRate: Int get() = output.sampleRate
    override val encoding: OutputEncoding get() = output.encoding
    override fun write(bytes: ByteArray, offset: Int, length: Int, timeoutMs: Int) = output.write(bytes, offset, length, timeoutMs)
    override fun resume() = output.resume()
    override fun pause() = output.pause()
    override fun flush() = output.flush()
    override fun status() = output.status()
    override fun close() = output.close()
    override fun capabilities(): IntArray? = runCatching { output.capabilities() }.getOrNull()
}
