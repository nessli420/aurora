package com.aurora.music.desktop.audio.decode

import com.aurora.music.desktop.platform.DesktopPaths
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swresample
import org.bytedeco.javacpp.Loader
import java.io.File

object FfmpegRuntime {
    @Volatile private var ready = false

    fun init(cacheDir: File = DesktopPaths.default().javacpp) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            cacheDir.mkdirs()
            System.setProperty("org.bytedeco.javacpp.cachedir", cacheDir.absolutePath)
            Loader.load(avutil::class.java)
            Loader.load(avcodec::class.java)
            Loader.load(avformat::class.java)
            Loader.load(swresample::class.java)
            avutil.av_log_set_level(avutil.AV_LOG_QUIET)
            avformat.avformat_network_init()
            ready = true
        }
    }
}
