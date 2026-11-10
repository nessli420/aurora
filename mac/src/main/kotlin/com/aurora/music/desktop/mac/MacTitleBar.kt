package com.aurora.music.desktop.mac

import javax.swing.JRootPane

// aurora paints its own bar under the traffic lights, the way it does on windows. the native title bar stays,
// only transparent and without its title, so the traffic lights, dragging, double-click zoom and full screen
// still belong to macos. the content runs to the window's top edge and the bar fills the 28pt the title bar covers.
object MacTitleBar {
    // read when the window's native peer is made, so this has to run before the window first shows
    fun install(root: JRootPane) {
        root.putClientProperty("apple.awt.fullWindowContent", true)
        root.putClientProperty("apple.awt.transparentTitleBar", true)
        // java 17 and newer; on older runtimes macos also draws "Aurora" in the middle of the bar
        root.putClientProperty("apple.awt.windowTitleVisible", false)
    }
}
