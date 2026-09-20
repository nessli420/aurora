package com.aurora.music.playback.dsd

internal object DsdUsbPolicy {
    fun carrierRate(vendor: Int, product: Int, bitRate: Int, wire: DsdWireFormat, experimental: Boolean): Int {
        val ka13 = vendor == 0x2972 && product == 0x0062
        require(ka13 || experimental) { "This DAC needs experimental DSD support enabled. Use PCM conversion otherwise." }
        val maximum = if (ka13) {
            if (wire == DsdWireFormat.DOP) 5_644_800 else 11_289_600
        } else 45_158_400
        require(bitRate in DsdFormat.supportedBitRates && bitRate <= maximum) {
            "This DSD rate exceeds the DAC's raw output range. Use PCM conversion."
        }
        return bitRate / (wire.sourceBytes * 8)
    }
}
