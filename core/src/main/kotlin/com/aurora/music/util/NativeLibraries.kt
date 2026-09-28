package com.aurora.music.util

object NativeLibraries {
    @Volatile var loader: (String) -> Unit = System::loadLibrary

    fun load(name: String) = loader(name)
}
