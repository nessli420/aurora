package com.aurora.music

import android.app.Application
import android.util.Log
import coil.size.pxOrElse
import com.aurora.music.data.AppContainer
import com.aurora.music.util.AppLog
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import kotlin.concurrent.thread

class AuroraApplication : Application(), coil.ImageLoaderFactory {
    private val built = FutureTask(Callable { AppContainer(this).also { it.aiDjMode } })
    @Volatile private var builder: Thread? = null

    // throws like the old lateinit before onCreate or from inside the build, so a provider never waits forever
    val container: AppContainer
        get() {
            val owner = builder
            if (owner == null || owner === Thread.currentThread()) throw UninitializedPropertyAccessException("container has not been initialized")
            return result()
        }

    private fun result(): AppContainer = try { built.get() } catch (e: ExecutionException) { throw e.cause ?: e }

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
        // main waits on it; raise only the nice value so threads it spawns keep default priority
        builder = thread(name = "container") {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
            built.run()
            result()
        }
    }

    // upgrading before coil's cache key also replaces small art saved in queues, history and playlists
    override fun newImageLoader() = coil.ImageLoader.Builder(this)
        .components {
            add(coil.map.Mapper<String, String> { data, options ->
                com.aurora.music.data.remote.YouTubeMusicParser.sized(data, options.size.width.pxOrElse { 0 }, options.size.height.pxOrElse { 0 })
            })
        }
        .okHttpClient { com.aurora.music.data.remote.AuroraHttp.base.newBuilder().addInterceptor(com.aurora.music.data.remote.YouTubeArtworkFallback).build() }
        .build()

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        com.aurora.music.localization.AppStrings.refresh()
    }
}
