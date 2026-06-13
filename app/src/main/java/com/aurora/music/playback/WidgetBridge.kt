package com.aurora.music.playback

import android.content.Context

/**
 * Decouples the playback service from the home-screen widget + Quick Settings tile. The service calls
 * [refresh] whenever the now-playing snapshot changes; the widget/tile layer (see
 * `ui/widget/`) pushes a fresh render. Failures are swallowed so playback is never affected by a
 * widget/tile that isn't present.
 */
object WidgetBridge {
    fun refresh(context: Context) {
        // Wired to the widget + tile layer in ui/widget/ (Phase 3.3). Guarded so a missing or failing
        // widget/tile can never affect playback.
        runCatching { com.aurora.music.ui.widget.AuroraWidgetController.refresh(context) }
    }
}
