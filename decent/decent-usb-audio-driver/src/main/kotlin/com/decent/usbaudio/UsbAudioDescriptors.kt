package com.decent.usbaudio

data class UsbRateRange(val minimum: Int, val maximum: Int, val step: Int) {
    fun contains(rate: Int): Boolean = rate in minimum..maximum && (step == 0 || (rate - minimum) % step == 0)
}

data class UsbAudioFormat(
    val configuration: Int,
    val interfaceId: Int,
    val alternateSetting: Int,
    val protocol: Int,
    val channels: Int,
    val validBits: Int,
    val containerBytes: Int,
    val endpointOut: Int,
    val endpointFeedback: Int,
    val maxPacketSize: Int,
    val interval: Int,
    val synchronization: Int,
    val controlInterfaceId: Int,
    val clockSourceId: Int,
    val clockControls: Int,
    val pcm: Boolean,
    val descriptorRates: List<UsbRateRange> = emptyList(),
    val formatBitmap: Long = if (pcm) 1 else 0,
    val formatType: Int = 1,
) {
    val containerBits get() = containerBytes * 8
    val unsupportedReason: String? get() = when {
        transportUnsupportedReason != null -> transportUnsupportedReason
        !pcm -> "The USB format is not integer PCM."
        containerBytes !in 2..4 || validBits !in 16..containerBits -> "Unsupported PCM sample layout."
        else -> null
    }
    val rawUnsupportedReason: String? get() = when {
        transportUnsupportedReason != null -> transportUnsupportedReason
        formatType != 1 || formatBitmap != 0x80000000L -> "The USB format is not Type I raw data."
        containerBytes != 4 || validBits != 32 -> "Unsupported raw USB sample layout."
        else -> null
    }
    val transportUnsupportedReason: String? get() = when {
        protocol != 0x20 -> "Direct output requires USB Audio Class 2."
        formatType != 1 -> "The USB format is not Type I."
        channels !in 1..2 -> "Direct output supports mono or stereo."
        interval != 1 -> "The USB endpoint interval is unsupported."
        maxPacketSize !in 1..512 -> "The USB packet size exceeds the driver budget."
        clockSourceId <= 0 || clockControls and 1 == 0 -> "The active USB clock cannot be verified."
        synchronization == 1 && endpointFeedback <= 0 -> "Explicit asynchronous feedback is required."
        else -> null
    }
    fun fits(rate: Int): Boolean = rate in 8000..384000 &&
        kotlin.math.ceil(rate * (if (synchronization == 1) 1.01 else 1.0) / 8000.0).toInt() * channels * containerBytes <= maxPacketSize
}

data class UsbClockStatus(val requestedHz: Int, val observedHz: Int?, val clockValid: Boolean?, val failure: String?) {
    val verified get() = failure == null && requestedHz == observedHz && clockValid != false
}

data class UsbDescriptorReport(val formats: List<UsbAudioFormat>, val malformed: Boolean)

object UsbAudioDescriptors {
    fun parse(raw: ByteArray): UsbDescriptorReport {
        data class Pending(val configuration: Int, val id: Int, val alt: Int, val protocol: Int, val control: Int,
            var channels: Int = 0, var bytes: Int = 0, var bits: Int = 0, var terminal: Int = 0,
            var pcm: Boolean = false, var out: Int = -1, var feedback: Int = -1, var packet: Int = 0,
            var interval: Int = 0, var sync: Int = 0, var rates: List<UsbRateRange> = emptyList(),
            var formats: Long = 0, var type: Int = 0)
        val pending = mutableListOf<Pending>()
        val clocks = mutableMapOf<Triple<Int, Int, Int>, Int>()
        val terminals = mutableMapOf<Triple<Int, Int, Int>, Int>()
        var configuration = 0; var control = -1; var interfaceClass = 0; var subclass = 0; var protocol = 0
        var stream: Pending? = null; var offset = 0; var malformed = false
        fun u(at: Int) = raw[at].toInt() and 255
        fun u24(at: Int) = u(at) or (u(at + 1) shl 8) or (u(at + 2) shl 16)
        while (offset < raw.size) {
            if (offset + 2 > raw.size) { malformed = true; break }
            val length = u(offset); val type = u(offset + 1)
            if (length < 2 || offset + length > raw.size) { malformed = true; break }
            when (type) {
                2 -> if (length >= 9) { configuration = u(offset + 5); control = -1; stream = null } else malformed = true
                4 -> if (length >= 9) {
                    interfaceClass = u(offset + 5); subclass = u(offset + 6); protocol = u(offset + 7)
                    stream = null
                    if (interfaceClass == 1 && subclass == 1) control = u(offset + 2)
                    if (interfaceClass == 1 && subclass == 2 && u(offset + 3) > 0) {
                        stream = Pending(configuration, u(offset + 2), u(offset + 3), protocol, control)
                        pending += stream!!
                    }
                } else malformed = true
                0x24 -> if (length >= 3 && interfaceClass == 1) {
                    val subtype = u(offset + 2)
                    if (subclass == 1 && protocol == 0x20) {
                        if (subtype == 0x0a && length >= 8) clocks[Triple(configuration, control, u(offset + 3))] = u(offset + 5)
                        if (subtype == 2 && length >= 17) terminals[Triple(configuration, control, u(offset + 3))] = u(offset + 7)
                        if (subtype == 3 && length >= 12) terminals[Triple(configuration, control, u(offset + 3))] = u(offset + 8)
                    }
                    stream?.let { s ->
                        if (subtype == 1) {
                            if (protocol == 0x20 && length >= 16) {
                                s.terminal = u(offset + 3); s.pcm = u(offset + 5) == 1 && u(offset + 6) and 1 != 0
                                s.type = u(offset + 5)
                                s.formats = (0..3).fold(0L) { bits, i -> bits or (u(offset + 6 + i).toLong() shl (8 * i)) }
                                s.channels = u(offset + 10)
                            } else if (protocol == 0 && length >= 7) { s.pcm = u(offset + 5) == 1 && u(offset + 6) == 0; s.type = 1; s.formats = if (s.pcm) 1 else 0 }
                        }
                        if (subtype == 2 && length >= 6 && u(offset + 3) == 1) {
                            if (protocol == 0x20) { s.bytes = u(offset + 4); s.bits = u(offset + 5) }
                            else if (protocol == 0 && length >= 8) {
                                s.channels = u(offset + 4); s.bytes = u(offset + 5); s.bits = u(offset + 6)
                                val count = u(offset + 7)
                                if (count == 0 && length >= 14) s.rates = listOf(UsbRateRange(u24(offset + 8), u24(offset + 11), 0))
                                else if (count > 0 && length >= 8 + count * 3) s.rates = List(count) { u24(offset + 8 + it * 3).let { rate -> UsbRateRange(rate, rate, 0) } }
                                else malformed = true
                            }
                        }
                    }
                }
                5 -> if (length >= 7) stream?.let { s ->
                    val address = u(offset + 2); val attributes = u(offset + 3)
                    if (attributes and 3 == 1) {
                        if (address and 0x80 == 0 && attributes and 0x30 == 0) {
                            s.out = address; val packet = u(offset + 4) or (u(offset + 5) shl 8)
                            s.packet = (packet and 0x7ff) * (1 + (packet shr 11 and 3))
                            s.interval = u(offset + 6); s.sync = attributes shr 2 and 3
                        } else if (address and 0x80 != 0 && attributes and 0x30 == 0x10) s.feedback = address
                    }
                } else malformed = true
            }
            offset += length
        }
        val formats = pending.filter { it.out > 0 && it.bytes > 0 && it.bits > 0 && it.channels > 0 }.map { s ->
            val clock = terminals[Triple(s.configuration, s.control, s.terminal)] ?: -1
            UsbAudioFormat(s.configuration, s.id, s.alt, s.protocol, s.channels, s.bits, s.bytes, s.out, s.feedback,
                s.packet, s.interval, s.sync, s.control, clock, clocks[Triple(s.configuration, s.control, clock)] ?: 0, s.pcm, s.rates, s.formats, s.type)
        }
        return UsbDescriptorReport(if (malformed) emptyList() else formats, malformed)
    }

    fun parseClockRanges(data: ByteArray): List<UsbRateRange> {
        require(data.size >= 2)
        val count = (data[0].toInt() and 255) or ((data[1].toInt() and 255) shl 8)
        require(count in 1..64 && data.size == 2 + count * 12)
        fun u32(offset: Int): Int {
            var value = 0L
            for (i in 0..3) value = value or ((data[offset + i].toLong() and 255) shl (i * 8))
            require(value <= Int.MAX_VALUE)
            return value.toInt()
        }
        return List(count) { index ->
            val offset = 2 + index * 12
            UsbRateRange(u32(offset), u32(offset + 4), u32(offset + 8)).also {
                require(it.minimum > 0 && it.maximum >= it.minimum)
            }
        }
    }
}
