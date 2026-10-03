package com.aurora.music.desktop.auth

import com.aurora.music.R
import com.aurora.music.data.ServerType
import com.aurora.music.desktop.platform.BuildInfo
import com.aurora.music.desktop.platform.HostPlatform
import com.aurora.music.desktop.platform.desktopClientInfo
import com.aurora.music.localization.AppStrings
import com.aurora.music.localization.appString
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

class AccountAuthenticatorTest {
    private val server = MockWebServer()
    private val authenticator = AccountAuthenticator(desktopClientInfo)
    private lateinit var base: String

    @Before fun setUp() {
        server.start()
        base = server.url("/").toString()
    }

    @After fun tearDown() = server.shutdown()

    private fun json(body: String, code: Int = 200) = server.enqueue(MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body))

    private fun failure(block: suspend () -> Unit): String = runBlocking {
        try {
            block()
            fail("Sign-in should fail")
            ""
        } catch (e: SignInException) {
            e.message.orEmpty()
        }
    }

    @Test fun subsonicPingBuildsATokenSession() = runBlocking {
        json("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}""")
        val session = authenticator.subsonic(base, " alice ", "secret")
        assertEquals(ServerType.SUBSONIC, session.type)
        assertEquals(base.trimEnd('/'), session.server)
        assertEquals("alice", session.username)
        val md5 = MessageDigest.getInstance("MD5").digest(("secret" + session.salt).toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(md5, session.token)
        val request = server.takeRequest()
        assertEquals("/rest/ping.view", request.requestUrl!!.encodedPath)
        assertEquals("alice", request.requestUrl!!.queryParameter("u"))
        assertEquals(md5, request.requestUrl!!.queryParameter("t"))
    }

    @Test fun subsonicRejectionsUseTheServerOrSharedMessages() {
        json("""{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"Wrong username or password"}}}""")
        assertEquals("Wrong username or password", failure { authenticator.subsonic(base, "alice", "bad") })
        json("""{"subsonic-response":{"status":"failed","version":"1.16.1"}}""")
        assertEquals(appString(R.string.text_login_rejected_3f73ee), failure { authenticator.subsonic(base, "alice", "bad") })
        json("{}", code = 401)
        assertEquals(appString(R.string.text_wrong_username_or_password_85b465), failure { authenticator.subsonic(base, "alice", "bad") })
        json("{}", code = 503)
        assertEquals(appString(R.string.text_server_error_c28ed6, 503), failure { authenticator.subsonic(base, "alice", "bad") })
    }

    @Test fun jellyfinAuthenticatesAsTheHostClient() = runBlocking {
        json("""{"AccessToken":"token-1","User":{"Id":"user-1","Name":"Alice"}}""")
        val session = authenticator.jellyfin(base, "alice", "secret")
        assertEquals(ServerType.JELLYFIN, session.type)
        assertEquals("token-1", session.token)
        assertEquals("user-1", session.userId)
        assertEquals("Alice", session.username)
        assertTrue(session.clientToken.startsWith("aurora-"))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/Users/AuthenticateByName", request.path)
        val header = request.getHeader("X-Emby-Authorization").orEmpty()
        assertTrue(header, header.contains("Client=\"Aurora\""))
        assertTrue(header, header.contains("Device=\"${HostPlatform.name}\""))
        assertTrue(header, header.contains("Version=\"${BuildInfo.VERSION_NAME}\""))
        assertTrue(header, header.contains("DeviceId=\"${session.clientToken}\""))
        assertTrue(request.body.readUtf8().contains("\"Username\":\"alice\""))
    }

    @Test fun jellyfinWrongPasswordIsReported() {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(appString(R.string.text_wrong_username_or_password_85b465), failure { authenticator.jellyfin(base, "alice", "bad") })
    }

    @Test fun networkFailuresMapToFriendlyMessages() {
        server.shutdown()
        assertEquals(appString(R.string.text_can_t_reach_the_server_1371a9), failure { authenticator.subsonic(base, "alice", "pw") })
        assertEquals(appString(R.string.text_server_not_found_check_the_address_24bc0a),
            failure { authenticator.jellyfin("http://aurora-sign-in-test.invalid", "alice", "pw") })
    }

    @Test fun plexRejectedTokensUseThePlexMessage() {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(appString(R.string.plex_token_rejected), failure { authenticator.plex(base, " plex-token ") })
    }

    @Test fun localAndUnsupportedTypes() {
        val local = authenticator.local()
        assertEquals(ServerType.LOCAL, local.type)
        assertEquals("local", local.token)
        assertTrue(local.isValid)
        try {
            AppStrings.setLocale("ru")
            assertEquals(local, authenticator.local())
        } finally {
            AppStrings.setLocale("")
        }
        assertEquals(appString(R.string.text_spotify_uses_the_connect_button_not_this_form_17d56a),
            failure { authenticator.signIn(ServerType.SPOTIFY, base, "a", "b") })
        assertEquals(setOf(ServerType.SUBSONIC, ServerType.JELLYFIN, ServerType.PLEX, ServerType.LOCAL), AccountAuthenticator.SUPPORTED)
    }
}
