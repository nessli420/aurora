package com.aurora.music.playback

import androidx.media3.common.C

data class MixerFormat(val sampleRate: Int, val encoding: Int, val channelMask: Int)

object BitPerfectMixerChoice {
    fun choose(supported: List<MixerFormat>, track: MixerFormat, exactOnly: Boolean): MixerFormat? {
        supported.firstOrNull { it == track }?.let { return it }
        if (exactOnly) return null
        return supported.filter { it.sampleRate == track.sampleRate && it.channelMask == track.channelMask }
            .maxByOrNull { precision(it.encoding) }
    }

    private fun precision(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_FLOAT -> 4
        C.ENCODING_PCM_32BIT -> 3
        C.ENCODING_PCM_24BIT -> 2
        C.ENCODING_PCM_16BIT -> 1
        else -> 0
    }
}
