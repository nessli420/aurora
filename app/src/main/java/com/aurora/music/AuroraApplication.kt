package com.aurora.music

import android.app.Application
import android.util.Log
import com.aurora.music.data.AppContainer
import com.aurora.music.util.AppLog

class AuroraApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        AppLog.sink = { level, tag, message, error ->
            when (level) {
                AppLog.Level.DEBUG -> if (error == null) Log.d(tag, message) else Log.d(tag, message, error)
                AppLog.Level.WARN -> if (error == null) Log.w(tag, message) else Log.w(tag, message, error)
                AppLog.Level.ERROR -> if (error == null) Log.e(tag, message) else Log.e(tag, message, error)
            }
        }
        com.aurora.music.localization.AppStrings.initialize(this)
        container = AppContainer(this)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        com.aurora.music.localization.AppStrings.refresh()
    }
}
