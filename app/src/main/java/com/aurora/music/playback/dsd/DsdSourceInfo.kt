package com.aurora.music.playback.dsd

import android.os.Parcel
import android.os.Parcelable
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi

@UnstableApi
data class DsdSourceInfo(val container: String, val bitRate: Int, val channels: Int, val sampleCount: Long) : Metadata.Entry {
    override fun describeContents(): Int = 0
    override fun writeToParcel(destination: Parcel, flags: Int) {
        destination.writeString(container); destination.writeInt(bitRate); destination.writeInt(channels); destination.writeLong(sampleCount)
    }
    companion object {
        @JvmField val CREATOR = object : Parcelable.Creator<DsdSourceInfo> {
            override fun createFromParcel(source: Parcel) = DsdSourceInfo(source.readString().orEmpty(), source.readInt(), source.readInt(), source.readLong())
            override fun newArray(size: Int): Array<DsdSourceInfo?> = arrayOfNulls(size)
        }
        fun from(format: Format?): DsdSourceInfo? {
            val metadata = format?.metadata ?: return null
            for (i in 0 until metadata.length()) (metadata[i] as? DsdSourceInfo)?.let { return it }
            return null
        }
    }
}
