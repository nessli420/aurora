package com.aurora.music.desktop.linux

import java.awt.Toolkit

object LinuxDesktop {
    const val ID = "aurora-Aurora"

    fun applyWindowClass(): Boolean = runCatching {
        val toolkit = Toolkit.getDefaultToolkit()
        toolkit.javaClass.getDeclaredField("awtAppClassName").apply { isAccessible = true }.set(null, ID)
    }.isSuccess
}
