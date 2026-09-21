package com.aurora.music

import android.app.Application
import com.aurora.music.data.AppContainer

class AuroraApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        com.aurora.music.localization.AppStrings.initialize(this)
        container = AppContainer(this)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        com.aurora.music.localization.AppStrings.refresh()
    }
}
