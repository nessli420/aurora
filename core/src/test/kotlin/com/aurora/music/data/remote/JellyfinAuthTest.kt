package com.aurora.music.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class JellyfinAuthTest {

    @Test fun usesTheStandardAuthorizationHeaderAndApiKeyParam() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"AccessToken":"tok en","User":{"Id":"user-id","Name":"Listener"}}"""))
            server.enqueue(MockResponse().setResponseCode(204))
            val session = JellyfinClient.authenticate(server.url("/").toString(), "Listener", "password", testClientInfo)

            val login = server.takeRequest()
            assertEquals("/Users/AuthenticateByName", login.path)
            assertNull(login.getHeader("X-Emby-Authorization"))
            assertEquals("MediaBrowser Client=\"Aurora\", Device=\"${testClientInfo.device}\", " +
                "DeviceId=\"${session.clientToken}\", Version=\"${testClientInfo.version}\"", login.getHeader("Authorization"))

            val client = JellyfinClient(session, testClientInfo)
            client.api.publicInfo()
            val authed = server.takeRequest()
            assertNull(authed.getHeader("X-Emby-Authorization"))
            assertTrue(authed.getHeader("Authorization")!!.endsWith(", Token=\"tok en\""))

            listOf(client.streamUrl("track", 0, true), client.streamUrl("track", 128, false), client.coverArtUrl("album")).forEach {
                val url = it.toHttpUrl()
                assertEquals("tok en", url.queryParameter("ApiKey"))
                assertNull(url.queryParameter("api_key"))
            }
        }
    }

    @Test fun quickConnectShowsTheCodeAndSignsInOnceApproved() = runBlocking {
        MockWebServer().use { server ->
            fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            server.enqueue(json("true"))
            server.enqueue(json("""{"Authenticated":false,"Secret":"s3cret","Code":"123456"}"""))
            server.enqueue(json("""{"Authenticated":false}"""))
            server.enqueue(json("""{"Authenticated":true}"""))
            server.enqueue(json("""{"AccessToken":"tok","User":{"Id":"user-id","Name":"Sso User"}}"""))
            var code: String? = null
            val session = JellyfinClient.quickConnect(server.url("/").toString(), testClientInfo, pollMillis = 1) { code = it }

            assertEquals("123456", code)
            assertEquals(listOf("/QuickConnect/Enabled", "/QuickConnect/Initiate", "/QuickConnect/Connect?secret=s3cret",
                "/QuickConnect/Connect?secret=s3cret", "/Users/AuthenticateWithQuickConnect"), List(5) { server.takeRequest().path })
            assertEquals("Sso User", session.username)
            assertEquals("tok", session.token)
            assertEquals("user-id", session.userId)
            assertTrue(session.clientToken!!.startsWith("aurora-"))
        }
    }

    @Test fun quickConnectReportsAnExpiredCodeAndADisabledServer() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("true"))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"Secret":"s","Code":"1"}"""))
            server.enqueue(MockResponse().setResponseCode(404))
            val expired = runCatching { JellyfinClient.quickConnect(server.url("/").toString(), testClientInfo, pollMillis = 1) {} }
            assertEquals(true, (expired.exceptionOrNull() as QuickConnectException).expired)

            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("false"))
            val disabled = runCatching { JellyfinClient.quickConnect(server.url("/").toString(), testClientInfo) {} }
            assertEquals(false, (disabled.exceptionOrNull() as QuickConnectException).expired)
        }
    }
}
