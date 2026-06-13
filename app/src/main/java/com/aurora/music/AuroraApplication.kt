package com.aurora.music

import android.app.Application
import com.aurora.music.data.AppContainer

class AuroraApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
