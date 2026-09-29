package com.aurora.music.desktop.natives

object SystemNative {
    init { NativeLoader.load("aurora_native") }

    external fun keepAwake(enabled: Boolean): Boolean
    external fun allowForeground(pid: Long): Boolean
    external fun protect(data: ByteArray, entropy: ByteArray?): ByteArray?
    external fun unprotect(data: ByteArray, entropy: ByteArray?): ByteArray?

    fun setAppUserModelId(id: String): Boolean = appUserModelId(id) == 0

    private external fun appUserModelId(id: String): Int
}
