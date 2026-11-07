package com.aurora.music.data.remote

import okhttp3.Interceptor
import okhttp3.Response

// low-res uploads have no maxresdefault and very old videos may lack mqdefault, so fall back to the always-present hqdefault
object YouTubeArtworkFallback : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val url = request.url
        if (response.code != 404 || url.host != "i.ytimg.com" || url.pathSegments.lastOrNull() !in setOf("maxresdefault.jpg", "mqdefault.jpg")) return response
        response.close()
        val fallback = url.newBuilder().setPathSegment(url.pathSize - 1, "hqdefault.jpg").build()
        return chain.proceed(request.newBuilder().url(fallback).build())
    }
}
