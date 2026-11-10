package com.aurora.music.desktop.mac

// while aurora sits in the menu bar it is an accessory app: no dock icon and no app switcher entry.
// it becomes a regular app again, and comes to the front, as soon as its window is shown
object MacDock {
    private const val REGULAR = 0L
    private const val ACCESSORY = 1L

    fun setVisible(visible: Boolean) = ObjC.onMain {
        val app = ObjC.msg(ObjC.cls("NSApplication"), "sharedApplication") ?: return@onMain
        ObjC.msg(app, "setActivationPolicy:", if (visible) REGULAR else ACCESSORY)
        if (visible) ObjC.msgVoid(app, "activateIgnoringOtherApps:", true)
    }
}
