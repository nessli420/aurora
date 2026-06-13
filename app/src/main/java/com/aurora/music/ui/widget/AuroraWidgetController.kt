package com.aurora.music.ui.widget

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Pushes a fresh render to the home-screen widget + Quick Settings tile when playback changes. */
object AuroraWidgetController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun refresh(context: Context) {
        val app = context.applicationContext
        scope.launch { runCatching { AuroraWidget().updateAll(app) } }
        runCatching {
            TileService.requestListeningState(app, ComponentName(app, AuroraTileService::class.java))
        }
    }
}
