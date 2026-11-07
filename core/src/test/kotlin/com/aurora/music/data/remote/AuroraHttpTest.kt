package com.aurora.music.data.remote

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class AuroraHttpTest {

    @Test fun baseKeepsThePlainClientSettingsItReplaced() {
        val plain = OkHttpClient()
        val base = AuroraHttp.base
        assertEquals(plain.connectTimeoutMillis, base.connectTimeoutMillis)
        assertEquals(plain.readTimeoutMillis, base.readTimeoutMillis)
        assertEquals(plain.writeTimeoutMillis, base.writeTimeoutMillis)
        assertEquals(plain.callTimeoutMillis, base.callTimeoutMillis)
        assertTrue(base.interceptors.isEmpty())
        assertTrue(base.followRedirects)
        assertEquals(12, base.dispatcher.maxRequestsPerHost)
    }

    @Test fun derivedClientsShareConnectionsAndTlsButKeepTheirOwnSettings() {
        val base = AuroraHttp.base
        val derived = base.newBuilder().followRedirects(false).readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { it.proceed(it.request()) }.build()
        assertSame(base.connectionPool, derived.connectionPool)
        assertSame(base.dispatcher, derived.dispatcher)
        assertSame(base.sslSocketFactory, derived.sslSocketFactory)
        assertEquals(30_000, derived.readTimeoutMillis)
        assertTrue(base.followRedirects)
        assertTrue(base.interceptors.isEmpty())
    }
}
