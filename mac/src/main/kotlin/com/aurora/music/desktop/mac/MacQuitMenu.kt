package com.aurora.music.desktop.mac

import com.sun.jna.Callback
import com.sun.jna.Pointer

// jcef swaps in its own -[NSApplication terminate:] once chromium loads, and the app menu's quit item sends exactly
// that, so ⌘Q never reached aurora. the item is pointed straight at aurora's quit instead
object MacQuitMenu {
    fun interface Action : Callback {
        fun invoke(self: Pointer, cmd: Pointer, sender: Pointer?)
    }

    @Volatile private var onQuit: () -> Unit = {}
    private val action = Action { _, _, _ -> onQuit() }
    private val targetClass: Pointer by lazy { ObjC.subclass("AuroraQuitTarget", mapOf("quit:" to (action to "v@:@"))) }
    private var target: Pointer? = null

    fun install(quit: () -> Unit) {
        onQuit = quit
        ObjC.onMain {
            val app = ObjC.msg(ObjC.cls("NSApplication"), "sharedApplication") ?: return@onMain
            val appMenu = ObjC.msg(ObjC.msg(ObjC.msg(app, "mainMenu"), "itemAtIndex:", 0L), "submenu") ?: return@onMain
            val item = (0 until ObjC.msgLong(appMenu, "numberOfItems")).map { ObjC.msg(appMenu, "itemAtIndex:", it)!! }
                .firstOrNull { ObjC.msg(ObjC.msg(it, "keyEquivalent"), "UTF8String")?.getString(0) == "q" } ?: return@onMain
            val receiver = target ?: checkNotNull(ObjC.msg(ObjC.msg(targetClass, "alloc"), "init")).also { target = it }
            ObjC.msg(item, "setTarget:", receiver)
            ObjC.msg(item, "setAction:", ObjC.sel("quit:"))
        }
    }

    /** Index of the quit item in the app menu, for tests. */
    internal fun quitItemIndex(): Long? {
        val app = ObjC.msg(ObjC.cls("NSApplication"), "sharedApplication") ?: return null
        val appMenu = ObjC.msg(ObjC.msg(ObjC.msg(app, "mainMenu"), "itemAtIndex:", 0L), "submenu") ?: return null
        return (0 until ObjC.msgLong(appMenu, "numberOfItems")).firstOrNull {
            ObjC.msg(ObjC.msg(ObjC.msg(appMenu, "itemAtIndex:", it), "keyEquivalent"), "UTF8String")?.getString(0) == "q"
        }
    }
}
