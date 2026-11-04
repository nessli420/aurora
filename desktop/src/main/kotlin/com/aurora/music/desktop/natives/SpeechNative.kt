package com.aurora.music.desktop.natives

object SpeechNative {
    init { NativeLoader.load("aurora_native") }

    external fun toWave(text: String, path: String): Boolean
}
