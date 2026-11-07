package com.aurora.music.data.remote

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object AuroraHttp {
    // okhttp defaults only, settings go on each newBuilder; a client that cancels or shuts down must not derive from this
    val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 12 })
            .build()
    }
}
