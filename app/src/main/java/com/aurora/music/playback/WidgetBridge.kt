package com.aurora.music.playback

import android.content.Context

/**
 * Decouples the playback service from the home-screen widget + Quick Settings tile. The service calls
 * [refresh] when the now-playing snapshot changes; the widget/tile layer (`ui/widget/`) pushes a
 * fresh render. Failures are swallowed so a missing widget/tile can never affect playback.
 */
object WidgetBridge {
    fun refresh(context: Context) {
        runCatching { com.aurora.music.ui.widget.AuroraWidgetController.refresh(context) }
    }
}
