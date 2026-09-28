package com.aurora.music.desktop.audio

import com.aurora.music.desktop.audio.decode.AudioTags
import com.aurora.music.model.Song
import kotlin.math.pow

object ReplayGain {
    const val OFF = 0
    const val TRACK = 1
    const val ALBUM = 2
    private const val R128_TO_REPLAYGAIN_DB = 5f

    fun gainDb(song: Song, tags: AudioTags?, mode: Int): Float? = when (mode) {
        TRACK -> song.replayGainTrack.known() ?: tags?.trackGainDb ?: tags?.r128TrackGainDb?.plus(R128_TO_REPLAYGAIN_DB)
        ALBUM -> song.replayGainAlbum.known() ?: tags?.albumGainDb ?: tags?.r128AlbumGainDb?.plus(R128_TO_REPLAYGAIN_DB)
        else -> null
    }

    fun multiplier(db: Float?): Double =
        if (db == null || !db.isFinite()) 1.0 else 10.0.pow(db / 20.0).coerceIn(0.1, 1.0)

    private fun Float.known() = takeIf { it != 0f && it.isFinite() }
}
