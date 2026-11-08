package com.aurora.music.data.merged

import com.aurora.music.data.MediaBackend
import com.aurora.music.data.PlaybackSourceIdentity
import com.aurora.music.data.ServerType
import com.aurora.music.data.Session
import com.aurora.music.data.mergeNamespace
import com.aurora.music.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

class SourceRouterTest {
    private val server = Session("https://server.invalid", "Listener", "", "token", ServerType.SUBSONIC)
    private val other = Session("https://other.invalid", "Listener", "", "token", ServerType.JELLYFIN)
    private val yt = Session("https://music.youtube.com", "Listener", "", "token", ServerType.YOUTUBE_MUSIC, userId = "account")
    private val outside = Session("https://elsewhere.invalid", "Listener", "", "token", ServerType.SUBSONIC)

    private fun source(session: Session): MediaBackend =
        Proxy.newProxyInstance(MediaBackend::class.java.classLoader, arrayOf(MediaBackend::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args!![0]
                "hashCode" -> System.identityHashCode(proxy)
                "getSession" -> session
                "playbackSourceIdentity" -> PlaybackSourceIdentity.fromSession(session, (args!![0] as Song).albumId)
                else -> error("Unexpected ${method.name}")
            }
        } as MediaBackend

    private fun routerOf(sessions: List<Session>, session: Session = sessions.first(), downUntil: ConcurrentHashMap<String, Long> = ConcurrentHashMap()) =
        SourceRouter(sessions.map(::source), session, nested = false, downUntil = downUntil)

    private fun song(id: String) = Song(id, "Title", "Artist", "Album", "", 180)

    @Test fun idsRoundTripAndForeignIdsDoNotRoute() {
        val router = routerOf(listOf(server, yt))
        val wrapped = router.wrapId(1, "x")
        assertEquals("${mergeNamespace(yt)}\u0001x", wrapped)
        assertEquals(1 to "x", router.unwrap(wrapped))
        assertEquals("", router.wrapId(0, ""))
        assertEquals(0 to "legacy", router.unwrap("0\u0001legacy"))
        assertNull(router.unwrap("9\u0001x"))
        assertNull(router.unwrap("plain"))
        assertNull(router.unwrap("\u0001x"))
        assertNull(router.unwrap("${mergeNamespace(outside)}\u0001x"))
    }

    @Test fun mixedKeepsLentSongsAndDropsSourcesNoLongerMerged() {
        val router = routerOf(listOf(server, yt))
        val lent = "${mergeNamespace(yt)}\u0001lent"
        val entries = listOf(song("plain"), song(lent), song("${mergeNamespace(outside)}\u0001gone"))
        assertEquals(listOf("${mergeNamespace(server)}\u0001plain", lent), router.mixed(entries, 0).map { it.id })
    }

    @Test fun aFailedSourceIsSkippedByEveryRouterSharingTheMap() = runBlocking {
        val shared = ConcurrentHashMap<String, Long>()
        val first = routerOf(listOf(other, server), server, shared)
        assertNull(first.reach<List<Int>>(0) { throw java.io.IOException("no route") })
        assertTrue(first.isDown(0))

        val second = routerOf(listOf(server, other), server, shared)
        assertTrue(second.isDown(1))
        var calls = 0
        assertNull(second.reach(1) { calls++; listOf(1) })
        assertEquals(0, calls)

        shared[mergeNamespace(other)] = 0L
        assertEquals(listOf(1), second.reach(1) { calls++; listOf(1) })
        assertEquals(1, calls)
        assertFalse(shared.containsKey(mergeNamespace(other)))
        assertFalse(first.isDown(0))
    }

    @Test fun searchFallsBackToAnotherCatalogueBeforeTheLibrary() {
        val withYouTube = routerOf(listOf(server, yt))
        assertEquals(1, withYouTube.searchIndex("spotify"))
        assertEquals(1, withYouTube.searchIndex("soundcloud"))
        assertEquals(1, withYouTube.searchIndex("discovery"))
        assertEquals(-1, withYouTube.searchIndex("library"))
        assertEquals(-1, routerOf(listOf(server)).searchIndex("discovery"))
    }

    @Test fun routeSendsPlainIdsToThePrimaryAndRefusesForeignOnes() = runBlocking {
        val router = routerOf(listOf(other, server), server)
        val plain = router.route("plain") { src, i, oid -> Triple(src, i, oid) }!!
        assertSame(router.sources[1], plain.first)
        assertEquals(1 to "plain", plain.second to plain.third)
        assertEquals(0 to "x", router.route(router.wrapId(0, "x")) { _, i, oid -> i to oid })

        var calls = 0
        assertNull(router.route("${mergeNamespace(outside)}\u0001x") { _, _, _ -> calls++ })
        assertEquals(0, calls)
        assertNull(router.route<Unit>("plain") { _, _, _ -> throw java.io.IOException("offline") })
    }
}
