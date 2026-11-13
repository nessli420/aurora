package com.aurora.music.data

import com.aurora.music.data.remote.SoundCloudTransport
import com.aurora.music.model.Song
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SoundCloudStandInTest {
    private val song = Song("sp", "Loving Machine", "TV Girl", "Who Really Cares", "", 227)

    private fun coding(protocol: String = "hls", snipped: Boolean = false) =
        """{"url":"https://api-v2.soundcloud.com/media/x/$protocol","preset":"aac_160k","snipped":$snipped,"format":{"protocol":"$protocol"}}"""
    private fun track(id: Int, title: String, user: String, seconds: Int, vararg codings: String = arrayOf(coding())) =
        """{"id":$id,"title":"$title","duration":${seconds * 1000},"full_duration":${seconds * 1000},"user":{"id":1,"username":"$user"},"media":{"transcodings":[${codings.joinToString(",")}]}}"""

    private class Api(private val tracks: List<String>) : SoundCloudTransport {
        val asked = mutableListOf<String>()
        var failSearch = false
        override suspend fun get(path: String): com.google.gson.JsonElement {
            asked += path
            return JsonParser.parseString(when {
                path.startsWith("/search/tracks") -> if (failSearch) throw java.io.IOException("offline") else """{"collection":[${tracks.joinToString(",")}]}"""
                path.startsWith("/tracks/") -> tracks.first { "{\"id\":${path.removePrefix("/tracks/")}," in it }.replaceFirst("{", "{\"track_authorization\":\"t\",")
                else -> """{"url":"https://playback.example/${path.substringAfter("media/x/").substringBefore('?')}.m3u8"}"""
            })
        }
        fun searches() = asked.count { it.startsWith("/search/") }
    }

    private fun standIn(api: Api, relaxed: Boolean = false) =
        SoundCloudStandIn({ SoundCloudBackend(Session.guest(ServerType.SOUNDCLOUD, "Guest"), api) }, { relaxed })

    @Test fun soundcloudIsAskedOnlyWhenYoutubeFails() = runBlocking {
        val api = Api(listOf(track(1, "Loving Machine", "TV Girl", 227)))
        val standIn = standIn(api)
        assertEquals("youtube", standIn.play(song, { "youtube" }) { it })
        assertTrue(api.asked.isEmpty())
        assertEquals("https://playback.example/hls.m3u8", standIn.play(song, { null }) { it })
        assertEquals(listOf("/tracks/1"), api.asked.filter { it.startsWith("/tracks/") })
    }

    @Test fun previewsAndDrmOnlyUploadsNeverStandIn() = runBlocking {
        val api = Api(listOf(
            track(1, "Loving Machine", "TV Girl", 227, coding(snipped = true)),
            track(2, "Loving Machine", "TV Girl", 227, coding("cbc-encrypted-hls")),
            track(3, "TV Girl - Loving Machine", "fan", 228),
        ))
        assertEquals(listOf("3"), SoundCloudBackend(Session.guest(ServerType.SOUNDCLOUD, "Guest"), api).fullTracks("Loving Machine TV Girl").map { it.id })
        assertNotNull(standIn(api).stream(song))
        assertEquals(listOf("/tracks/3"), api.asked.filter { it.startsWith("/tracks/") })
    }

    @Test fun onlyTheSameRecordingMatchesWithTheArtistsOwnUploadFirst() {
        fun sc(id: String, title: String, artist: String, seconds: Int) = Song(id, title, artist, "", "", seconds)
        val candidates = listOf(
            sc("repost", "TV Girl - Loving Machine", "fan", 228),
            sc("remix", "Loving Machine (Remix)", "TV Girl", 227),
            sc("slowed", "tv girl - loving machine (slowed)", "idk", 244),
            sc("instrumental", "TV Girl - Loving Machine - Instrumental", "isobel", 227),
            sc("cover", "Loving Machine", "Someone Else", 226),
            sc("long", "Loving Machine", "TV Girl", 233),
            sc("official", "Loving Machine", "TV Girl", 226),
        )
        assertEquals(listOf("official", "repost"), SoundCloudStandIn.matches(song, candidates, relaxed = false).map { it.id })
        assertEquals(listOf("official", "repost", "cover"), SoundCloudStandIn.matches(song, candidates, relaxed = true).map { it.id })
        assertTrue(SoundCloudStandIn.matches(song.copy(durationSec = 0), candidates, relaxed = true).isEmpty())
    }

    @Test fun nothingConfidentMeansNoStandIn() = runBlocking {
        val api = Api(listOf(track(1, "Loving Machine (sped up)", "TV Girl", 216), track(2, "Loving Machine", "TV Girl", 250)))
        assertNull(standIn(api).stream(song))
        assertTrue(api.asked.none { it.startsWith("/tracks/") })
    }

    @Test fun aMatchOrAMissIsRememberedButAFailedSearchIsNot() = runBlocking {
        val hit = Api(listOf(track(1, "Loving Machine", "TV Girl", 227)))
        val standIn = standIn(hit)
        repeat(2) { assertNotNull(standIn.stream(song)) }
        assertEquals(1, hit.searches())
        assertEquals(2, hit.asked.count { it == "/tracks/1" })

        val miss = Api(emptyList())
        val missing = standIn(miss)
        repeat(2) { assertNull(missing.stream(song)) }
        assertEquals(1, miss.searches())

        val down = Api(listOf(track(1, "Loving Machine", "TV Girl", 227))).apply { failSearch = true }
        val offline = standIn(down)
        assertNull(offline.stream(song))
        down.failSearch = false
        assertNotNull(offline.stream(song))
        assertEquals(2, down.searches())
    }
}
