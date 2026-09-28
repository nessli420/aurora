package com.aurora.music.desktop.natives

import java.io.File

object NativeLoader {
    private val loaded = mutableSetOf<String>()

    val directory: File?
        get() = System.getProperty("compose.application.resources.dir")?.let(::File)

    @Synchronized
    fun load(name: String) {
        if (name in loaded) return
        val file = directory?.resolve(System.mapLibraryName(name))
        if (file != null && file.isFile) System.load(file.absolutePath) else System.loadLibrary(name)
        loaded += name
    }
}
