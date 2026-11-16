package com.aurora.music.data

import com.aurora.music.data.remote.AppleMusicClient
import com.aurora.music.data.remote.AppleMusicTransport
import com.aurora.music.data.remote.AppleMusicWebSession
import com.aurora.music.data.remote.obj
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Base64

class AppleMusicBackendTest {
    private val guest = Session.guest(ServerType.APPLE_MUSIC, "Guest")
    private fun fixture(name: String) = javaClass.getResourceAsStream("/apple-music/$name")!!.bufferedReader().use { JsonParser.parseString(it.readText()).asJsonObject }

    private fun backend(asked: MutableList<String> = mutableListOf(), answer: (String) -> JsonObject) = AppleMusicBackend(guest, object : AppleMusicTransport {
        override suspend fun get(path: String): JsonObject { asked += path; return answer(path) }
        override suspend fun storefront() = "us"
    })

    @Test fun searchMapsSongsWithTheirAlbumArtistAndAYoutubeMatch() = runBlocking {
        val asked = mutableListOf<String>()
        val results = backend(asked) { fixture("search.json") }.search("tv girl")
        val song = results.songs.first()
        assertEquals("1623924746", song.id)
        assertEquals("Cigarettes out the Window", song.title)
        assertEquals("TV Girl", song.artist)
        assertEquals(198, song.durationSec)
        assertEquals("1623924742", song.albumId)
        assertEquals("468460706", song.artistId)
        assertTrue(song.artworkUrl.endsWith("/600x600bb.jpg"))
        assertEquals("aurora-yt://am1623924746?q=Cigarettes+out+the+Window+TV+Girl&dur=198", song.streamUrl)
        assertEquals(listOf("Who Really Cares", "French Exit"), results.albums.map { it.title })
        assertEquals("TV Girl", results.artists.single().name)
        assertEquals("pl.65568a06f6db4a32b5beaaffd9cc1804", results.playlists.single().id)
        assertEquals("Apple Music Indie", results.playlists.single().subtitle)
        assertTrue(asked.single().startsWith("/v1/catalog/us/search?term=tv+girl&types=songs,albums,artists,playlists"))
    }

    @Test fun albumFollowsItsNextPageAndLendsTracksItsArtist() = runBlocking {
        val asked = mutableListOf<String>()
        val next = """{"data":[{"id":"1623924747","type":"songs","attributes":{"name":"Not Allowed","artistName":"TV Girl","albumName":"Who Really Cares","durationInMillis":178000,"artwork":{"url":"https://a/{w}x{h}bb.jpg"}}}]}"""
        val detail = backend(asked) { path -> if ("offset=3" in path) JsonParser.parseString(next).asJsonObject else fixture("album.json") }.detail("album", "1623924742")!!
        assertEquals(listOf("1623924744", "1623924745", "1623924746", "1623924747"), detail.tracks.map { it.id })
        assertTrue(detail.tracks.all { it.albumId == "1623924742" && it.album == "Who Really Cares" && it.artistId == "468460706" })
        assertEquals("Who Really Cares", detail.info.title)
        assertEquals("TV Girl", detail.info.subtitle)
        assertEquals(listOf("/v1/catalog/us/albums/1623924742?include[songs]=artists", "/v1/catalog/us/albums/1623924742/tracks?offset=3"), asked)
    }

    @Test fun playlistTracksTakeTheirAlbumFromTheSongLinkAndCannotBeEdited() = runBlocking {
        val detail = backend { fixture("playlist.json") }.detail("playlist", "pl.f4d106fed2bd41149aaacabb233eb5eb")!!
        assertEquals("Today’s Hits", detail.info.title)
        assertEquals("Apple Music Hits", detail.info.subtitle)
        assertFalse(detail.info.editable)
        val first = detail.tracks.first()
        assertEquals("6822497774", first.id)
        assertEquals("6822497770", first.albumId)
        assertEquals("1446365464", first.artistId)
    }

    @Test fun artistPageListsTopSongsAndReleases() = runBlocking {
        val detail = backend { fixture("artist.json") }.detail("artist", "468460706")!!
        assertTrue(detail.info.isArtist)
        assertEquals("TV Girl", detail.info.title)
        assertEquals(listOf("1623929730", "1623924746"), detail.tracks.map { it.id })
        assertTrue(detail.tracks.all { it.artistId == "468460706" })
        assertEquals(listOf("1800848661", "1689495881"), detail.albums.map { it.id })
    }

    @Test fun homeShowsBrowseShelvesThenChartsAndSkipsBanners() = runBlocking {
        val home = backend { path -> if ("groupings" in path) fixture("groupings.json") else fixture("charts.json") }.home()
        assertEquals(listOf("Best New Songs", "New This Week", "Updated Playlists", "Top Songs", "Top Albums", "Top Playlists"), home.sections.map { it.title })
        assertTrue(home.sections[0].items.all { it is HomeFeedItem.Track })
        assertTrue(home.sections[1].items.all { it is HomeFeedItem.Record })
        assertTrue(home.sections[2].items.all { it is HomeFeedItem.Collection })
    }

    @Test fun homeFailsOnlyWhenNothingLoads() {
        assertThrows(IOException::class.java) { runBlocking { backend { throw IOException("offline") }.home() } }
    }

    @Test fun radioTakesTheSeedArtistAndSimilarArtistsInTurn() = runBlocking {
        fun song(id: String, artist: String) = """{"id":"$id","type":"songs","attributes":{"name":"Song $id","artistName":"$artist","durationInMillis":200000,"artwork":{"url":""}}}"""
        val seed = """{"data":[{"id":"1","type":"songs","attributes":{"name":"Seed","artistName":"A","durationInMillis":1000},"relationships":{"artists":{"data":[{"id":"10","type":"artists"}]}}}]}"""
        val artist = """{"data":[{"id":"10","type":"artists","views":{"top-songs":{"data":[${song("1", "A")},${song("2", "A")}]},"similar-artists":{"data":[{"id":"20","type":"artists"}]}}}]}"""
        val similar = """{"data":[{"id":"20","type":"artists","views":{"top-songs":{"data":[${song("3", "B")},${song("4", "B")}]}}}]}"""
        val radio = backend { path ->
            JsonParser.parseString(when {
                path.startsWith("/v1/catalog/us/songs/1") -> seed
                path.startsWith("/v1/catalog/us/artists/10") -> artist
                path.startsWith("/v1/catalog/us/artists?ids=20") -> similar
                else -> error(path)
            }).asJsonObject
        }.radio("1")
        assertEquals(listOf("3", "2", "4"), radio.map { it.id })
        assertEquals("20", radio.first().artistId)
    }

    @Test fun releaseSuffixesBecomeReleaseTypes() {
        fun album(name: String) = AppleMusicBackend.album(JsonParser.parseString("""{"id":"1","type":"albums","attributes":{"name":"$name","artistName":"X","trackCount":2}}""").asJsonObject)!!
        assertEquals("Carnaval De Luz" to "single", album("Carnaval De Luz - Single").let { it.title to it.releaseType })
        assertEquals("Five" to "ep", album("Five - EP").let { it.title to it.releaseType })
        assertNull(AppleMusicBackend.song(JsonParser.parseString("""{"id":"1","type":"music-videos","attributes":{"name":"Clip"}}""").asJsonObject))
    }

    @Test fun webSessionComesFromTheSignedInCookies() {
        val session = AppleMusicWebSession.fromCookies("geo=NL; media-user-token=AbC+12/x==; itua=NL")!!
        assertEquals("AbC+12/x==", session.userToken)
        assertEquals("nl", session.storefront)
        assertEquals(session.userToken, AppleMusicWebSession.decode(session.encode()).userToken)
        assertNull(AppleMusicWebSession.fromCookies("itua=US; dslang=US-EN"))
        assertEquals("us", AppleMusicWebSession.fromCookies("media-user-token=x")!!.storefront)
        assertThrows(IOException::class.java) { AppleMusicWebSession.decode("""{"userToken":"","storefront":"nl"}""") }
    }

    private val account = Session(AppleMusicClient.WEB, "Listener", "", "login-ref", ServerType.APPLE_MUSIC, "u1")
    private fun signedIn(active: Boolean, asked: MutableList<String> = mutableListOf(), answer: (String) -> String) = AppleMusicBackend(account, object : AppleMusicTransport {
        override suspend fun get(path: String): JsonObject {
            asked += path
            val body = if (path.startsWith("/v1/me/account")) """{"meta":{"subscription":{"active":$active,"storefront":"nl"}}}""" else answer(path)
            return JsonParser.parseString(body).asJsonObject
        }
        override suspend fun storefront() = "nl"
    })
    private fun librarySong(id: String, name: String, catalogId: String? = null, catalog: String? = null) =
        """{"id":"$id","type":"library-songs","attributes":{"name":"$name","artistName":"TV Girl","albumName":"Who Really Cares","durationInMillis":227000,""" +
            """"artwork":{"url":"https://a/{w}x{h}bb.jpg"},"playParams":{"id":"$id","kind":"song","isLibrary":true${catalogId?.let { ",\"catalogId\":\"$it\"" } ?: ""}}}""" +
            (catalog?.let { ""","relationships":{"catalog":{"data":[$it]}}""" } ?: "") + "}"

    @Test fun librarySongsKeepTheirCatalogueIdSoTheyPlayAndLinkLikeAnyOther() {
        val catalogue = """{"id":"1623924754","type":"songs","attributes":{"name":"Loving Machine","artistName":"TV Girl","durationInMillis":227000,"url":"https://music.apple.com/nl/album/x/1623924742?i=1623924754","artwork":{"url":""}}}"""
        val matched = AppleMusicBackend.librarySong(JsonParser.parseString(librarySong("i.AAA", "Loving Machine", catalog = catalogue)).asJsonObject)!!
        assertEquals("1623924754", matched.id)
        assertEquals("1623924742", matched.albumId)
        assertEquals("1623924754", AppleMusicBackend.librarySong(JsonParser.parseString(librarySong("i.BBB", "Loving Machine", "1623924754")).asJsonObject)!!.id)
        val upload = AppleMusicBackend.librarySong(JsonParser.parseString(librarySong("i.CCC", "Home Demo")).asJsonObject)!!
        assertEquals("i.CCC", upload.id)
        assertEquals("aurora-yt://ami.CCC?q=Home+Demo+TV+Girl&dur=227", upload.streamUrl)
    }

    @Test fun withoutASubscriptionTheLibraryStaysEmptyAndIsNeverAsked() = runBlocking {
        val asked = mutableListOf<String>()
        val backend = signedIn(active = false, asked) { error("asked for $it") }
        assertTrue(backend.allSongs().isEmpty())
        assertTrue(backend.allPlaylists().isEmpty())
        assertTrue(backend.starredSongs().isEmpty())
        assertTrue(backend.likedSongIds(listOf("1")).isEmpty())
        assertNull(backend.serverLyrics(AppleMusicBackend.song(JsonParser.parseString("""{"id":"1","type":"songs","attributes":{"name":"x"}}""").asJsonObject)!!))
        assertEquals(listOf("/v1/me/account?meta=subscription"), asked)
    }

    @Test fun favoriteSongsAreTheLikedSongsAndLeaveThePlaylistList() = runBlocking {
        // as apple lists them: the tag only comes when asked for, and the name follows the storefront's spelling
        val favourites = """{"id":"p.FAV","type":"library-playlists","attributes":{"canDelete":false,"canEdit":false,"hasCatalog":false,"isPublic":false,""" +
            """"name":"Favourite Songs","playParams":{"id":"p.FAV","isLibrary":true,"kind":"playlist"}${'$'}TAGS}}"""
        val mix = """{"id":"p.MIX","type":"library-playlists","attributes":{"name":"Road trip","canEdit":true}}"""
        val asked = mutableListOf<String>()
        val backend = signedIn(active = true, asked) { path ->
            when {
                path.startsWith("/v1/me/library/playlists?") ->
                    """{"data":[${favourites.replace("\$TAGS", if ("extend=tags" in path) ",\"tags\":[\"favorited\"]" else "")},$mix]}"""
                path == "/v1/me/library/playlists/p.FAV/tracks?limit=100&include=catalog&offset=0" ->
                    """{"data":[${librarySong("i.1", "One", "11")}],"next":"/v1/me/library/playlists/p.FAV/tracks?offset=1"}"""
                path == "/v1/me/library/playlists/p.FAV/tracks?limit=100&include=catalog&offset=1" -> """{"data":[${librarySong("i.2", "Two", "22")}]}"""
                else -> error(path)
            }
        }
        assertEquals(listOf("p.MIX"), backend.allPlaylists().map { it.id })
        val liked = backend.starredSongs()
        assertEquals(listOf("11", "22"), liked.map { it.id })
        assertTrue(liked.all { it.liked })
        assertEquals(2, backend.starredCount())
        assertEquals(1, asked.count { it.startsWith("/v1/me/library/playlists?") })
    }

    @Test fun favouritesAreTheSongsRatedOne() = runBlocking {
        val backend = signedIn(active = true) { path ->
            assertTrue(path.startsWith("/v1/catalog/nl?ids[songs]=1,2,3&fields[songs]=personalRating"))
            """{"data":[{"id":"1","type":"songs","attributes":{"personalRating":1}},{"id":"2","type":"songs","attributes":{"personalRating":-1}},{"id":"3","type":"songs","attributes":{}}]}"""
        }
        assertEquals(setOf("1"), backend.likedSongIds(listOf("1", "2", "3", "i.local")))
    }

    @Test fun wordSyncedLyricsKeepEachWordsTiming() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml" itunes:timing="Word"><body><div begin="1.000" end="9.000">""" +
            """<p begin="1.000" end="3.500"><span begin="1.000" end="1.400">Can&apos;t</span> <span begin="1.400" end="2:00.000">stop</span></p>""" +
            """<p begin="1:02.500" end="1:04.000"><span begin="1:02.500" end="1:03.000">ex</span><span begin="1:03.000" end="1:04.000">ample</span></p>""" +
            """</div></body></tt>"""
        val lyrics = AppleMusicBackend.lyrics(ttml)!!
        assertTrue(lyrics.synced)
        assertEquals(listOf("Can't stop", "example"), lyrics.lines.map { it.text })
        assertEquals(62_500L, lyrics.lines[1].startMs)
        assertEquals(62, lyrics.lines[1].timeSec)
        assertEquals(listOf("Can't ", "stop"), lyrics.lines[0].words.map { it.text })
        assertEquals(1_400L to 120_000L, lyrics.lines[0].words[1].let { it.startMs to it.endMs })
        assertEquals("example", lyrics.lines[1].displayText)
    }

    @Test fun lineAndUnsyncedLyricsAndClockValues() {
        val lines = AppleMusicBackend.lyrics("""<tt itunes:timing="Line"><body><div><p begin="00:12.345" end="00:14.000">First &amp; last</p><p begin="15.5s" end="16s">Second</p></div></body></tt>""")!!
        assertTrue(lines.synced)
        assertEquals(listOf(12_345L, 15_500L), lines.lines.map { it.startMs })
        assertEquals("First & last", lines.lines[0].text)
        val plain = AppleMusicBackend.lyrics("""<tt itunes:timing="None"><body><div><p>Just words</p><p>More words</p></div></body></tt>""")!!
        assertFalse(plain.synced)
        assertEquals(listOf(-1, -1), plain.lines.map { it.timeSec })
        assertNull(AppleMusicBackend.lyrics("<tt><body></body></tt>"))
        assertEquals(3_723_456L, AppleMusicBackend.clock("1:02:03.456"))
        assertNull(AppleMusicBackend.clock(""))
    }

    private class Sent(val method: String, val path: String, val body: String?)
    private fun writable(active: Boolean, sent: MutableList<Sent>, likes: GuestLikes? = null, answer: (String) -> String = { error(it) }) = AppleMusicBackend(account, object : AppleMusicTransport {
        override suspend fun get(path: String): JsonObject = JsonParser.parseString(
            if (path.startsWith("/v1/me/account")) """{"meta":{"subscription":{"active":$active}}}""" else answer(path)).asJsonObject
        override suspend fun storefront() = "nl"
        override suspend fun send(method: String, path: String, body: JsonObject?): JsonObject {
            sent += Sent(method, path, body?.toString())
            return JsonParser.parseString(if (method == "POST" && path == "/v1/me/library/playlists") """{"data":[{"id":"p.NEW","type":"library-playlists"}]}""" else "{}").asJsonObject
        }
    }, guestLikes = likes)

    @Test fun aGuestKeepsItsLikesOnTheDevice() = runBlocking {
        val likes = GuestLikes()
        val backend = AppleMusicBackend(guest, object : AppleMusicTransport {
            override suspend fun get(path: String) = fixture("search.json").obj("results").obj("songs").let {
                JsonParser.parseString("""{"data":[${it.getAsJsonArray("data")[0]}]}""").asJsonObject }
            override suspend fun storefront() = "us"
        }, guestLikes = likes)
        assertFalse(backend.signedIn)
        assertTrue(backend.likesLocally("1623924746"))
        assertFalse(backend.likesLocally("i.local"))
        assertTrue(backend.setStarred("1623924746", true, "song"))
        assertEquals(listOf("1623924746"), backend.starredSongs().map { it.id })
        assertEquals(setOf("1623924746"), backend.likedSongIds(listOf("1623924746", "9")))
        assertTrue(backend.setStarred("1623924746", false, "song"))
        assertEquals(0, backend.starredCount())
    }

    @Test fun favouritesGoThroughTheWebPlayersFavoritesEndpoint() = runBlocking {
        val sent = mutableListOf<Sent>()
        val backend = writable(active = true, sent)
        assertTrue(backend.setStarred("1623924754", true, "song"))
        assertTrue(backend.setStarred("p.MIX", false, "playlist"))
        assertTrue(backend.setStarred("l.ALBUM", true, "album"))
        assertTrue(backend.setStarred("pl.f4d106fed2bd41149aaacabb233eb5eb", true, "playlist"))
        assertEquals(listOf("POST /v1/me/favorites?ids[songs]=1623924754", "DELETE /v1/me/favorites?ids[library-playlists]=p.MIX",
            "POST /v1/me/favorites?ids[library-albums]=l.ALBUM", "POST /v1/me/favorites?ids[playlists]=pl.f4d106fed2bd41149aaacabb233eb5eb"),
            sent.map { "${it.method} ${it.path}" })
        assertTrue(backend.signedIn)
    }

    @Test fun withoutASubscriptionLikesAndPlaylistsStayOnTheDevice() = runBlocking {
        val sent = mutableListOf<Sent>()
        val backend = writable(active = false, sent, GuestLikes()) { path ->
            val id = path.substringAfter("/v1/catalog/nl/songs/", "").ifEmpty { error(path) }
            """{"data":[{"id":"$id","type":"songs","attributes":{"name":"Song $id","artistName":"A","durationInMillis":100000}}]}"""
        }
        assertTrue(backend.signedIn)
        assertFalse(backend.likesLocally("1"))
        assertTrue(backend.setStarred("1", true, "song"))
        assertTrue(backend.setStarred("77", true, "album"))
        assertEquals(setOf("1", "77"), backend.starredIds())
        assertEquals(listOf("1"), backend.starredSongs().map { it.id })
        val id = backend.createPlaylistWithId("Road trip", listOf("1"))!!
        // a merge hands its own copy, with the album id still namespaced
        assertTrue(backend.addSongsToPlaylist(id, listOf(com.aurora.music.model.Song("2", "Two", "B", "", "", 100, albumId = "apple${MERGE_NAMESPACE_SEP}9"))))
        assertTrue(backend.addToPlaylist(id, listOf("3")))
        assertTrue(backend.removeFromPlaylist(id, listOf("1")))
        assertTrue(backend.updatePlaylist(id, " Late night ", "for the drive"))
        assertEquals(listOf(id), backend.allPlaylists().map { it.id })
        assertEquals(listOf(id), backend.playlistsForSong("2").map { it.id })
        val detail = backend.detail("playlist", id)!!
        assertEquals("Late night" to "for the drive", detail.info.title to detail.info.editableDescription)
        assertTrue(detail.info.editable)
        assertEquals(listOf("2", "3"), detail.tracks.map { it.id })
        assertTrue(detail.tracks.none { MERGE_NAMESPACE_SEP in it.albumId })
        assertTrue(backend.deletePlaylist(id))
        assertTrue(backend.allPlaylists().isEmpty())
        assertTrue(sent.isEmpty())
    }

    @Test fun withoutAnyPlaceToKeepThemNothingIsSent() = runBlocking {
        val sent = mutableListOf<Sent>()
        val backend = writable(active = false, sent)
        assertFalse(backend.setStarred("1", true, "song"))
        assertNull(backend.createPlaylistWithId("Road trip", listOf("1")))
        assertFalse(backend.addToPlaylist("p.MIX", listOf("1")))
        assertFalse(backend.deletePlaylist("p.MIX"))
        assertTrue(sent.isEmpty())
    }

    @Test fun playlistEditsUseTheLibraryEndpoints() = runBlocking {
        val sent = mutableListOf<Sent>()
        val tracks = """{"data":[${librarySong("i.A", "One", "11")},${librarySong("i.B", "Two")},${librarySong("i.C", "Three", "33")}]}"""
        val backend = writable(active = true, sent) { path ->
            if (path.startsWith("/v1/me/library/playlists/p.MIX/tracks?")) tracks else error(path)
        }
        assertEquals("p.NEW", backend.createPlaylistWithId("Road trip", listOf("11", "i.B")))
        assertTrue(backend.addToPlaylist("p.MIX", listOf("33")))
        assertTrue(backend.updatePlaylist("p.MIX", " Late night ", null))
        assertTrue(backend.removeFromPlaylist("p.MIX", listOf("11", "i.B")))
        assertFalse(backend.removeFromPlaylist("p.MIX", listOf("999")))
        assertTrue(backend.deletePlaylist("p.MIX"))
        assertEquals(listOf(
            "POST /v1/me/library/playlists {\"attributes\":{\"name\":\"Road trip\",\"description\":\"\"},\"relationships\":{\"tracks\":{\"data\":[{\"id\":\"11\",\"type\":\"songs\"},{\"id\":\"i.B\",\"type\":\"library-songs\"}]}}}",
            "POST /v1/me/library/playlists/p.MIX/tracks?representation=resources {\"data\":[{\"id\":\"33\",\"type\":\"songs\"}]}",
            "PATCH /v1/me/library/playlists/p.MIX {\"attributes\":{\"name\":\"Late night\"}}",
            "DELETE /v1/me/library/playlists/p.MIX/tracks?ids[library-songs]=i.A&mode=all null",
            "DELETE /v1/me/library/playlists/p.MIX/tracks?ids[library-songs]=i.B&mode=all null",
            "DELETE /v1/me/library/playlists/p.MIX null",
        ), sent.map { "${it.method} ${it.path} ${it.body}" })
    }

    @Test fun aPlaylistJustMadeOrDeletedShowsSoBeforeAppleListsIt() = runBlocking {
        val backend = writable(active = true, mutableListOf()) { path ->
            if (path.startsWith("/v1/me/library/playlists?")) """{"data":[{"id":"p.MIX","type":"library-playlists","attributes":{"name":"Road trip","canEdit":true}}]}"""
            else error(path)
        }
        assertEquals("p.NEW", backend.createPlaylistWithId("Late night", emptyList()))
        assertTrue(backend.deletePlaylist("p.MIX"))
        assertEquals(listOf("p.NEW" to "Late night"), backend.allPlaylists().map { it.id to it.title })
        assertEquals(listOf("p.NEW"), backend.playlistsForSong("1").map { it.id })
    }

    @Test fun whatTheDeviceKeptWaitsForAnAccountWithASubscription() = runBlocking {
        val apple = ServerType.APPLE_MUSIC
        val likes = GuestLikes()
        likes.add(apple, com.aurora.music.model.Song("1", "One", "A", "", "", 200))
        likes.setCollectionLiked(apple, "album", "77", true)
        val mix = likes.createPlaylist(apple, "Road trip", listOf("2", "3").map { com.aurora.music.model.Song(it, "Song $it", "A", "", "", 200) })
        likes.changePlaylist(apple, mix) { it.copy(description = "for the drive") }
        val refused = mutableListOf<Sent>()
        assertEquals(0, likes.moveTo(writable(active = false, refused, likes), pauseMs = 0))
        assertEquals(3, likes.pending(apple))
        assertTrue(refused.isEmpty())
        val sent = mutableListOf<Sent>()
        assertEquals(3, likes.moveTo(writable(active = true, sent, likes), pauseMs = 0))
        assertEquals(listOf(
            "POST /v1/me/favorites?ids[songs]=1 null",
            "POST /v1/me/favorites?ids[albums]=77 null",
            "POST /v1/me/library/playlists {\"attributes\":{\"name\":\"Road trip\",\"description\":\"\"}}",
            "PATCH /v1/me/library/playlists/p.NEW {\"attributes\":{\"description\":\"for the drive\"}}",
            "POST /v1/me/library/playlists/p.NEW/tracks?representation=resources {\"data\":[{\"id\":\"2\",\"type\":\"songs\"},{\"id\":\"3\",\"type\":\"songs\"}]}",
        ), sent.map { "${it.method} ${it.path} ${it.body}" })
        assertEquals(0, likes.pending(apple))
    }

    @Test fun onlyPlaylistsTheAccountMadeCanBeEdited() = runBlocking {
        val backend = writable(active = true, mutableListOf()) { path ->
            when {
                path.startsWith("/v1/me/library/playlists?") -> """{"data":[{"id":"p.FAV","type":"library-playlists","attributes":{"name":"Favorite Songs","canEdit":true,"tags":["favorited"]}},""" +
                    """{"id":"p.MINE","type":"library-playlists","attributes":{"name":"Mine","canEdit":true}},{"id":"p.SAVED","type":"library-playlists","attributes":{"name":"Today's Hits","canEdit":false}}]}"""
                path.startsWith("/v1/me/library/playlists/p.MINE?") || path == "/v1/me/library/playlists/p.MINE" ->
                    """{"data":[{"id":"p.MINE","type":"library-playlists","attributes":{"name":"Mine","canEdit":true,"description":{"standard":"Late"}}}]}"""
                path.startsWith("/v1/me/library/playlists/p.MINE/tracks") -> """{"data":[]}"""
                else -> error(path)
            }
        }
        assertEquals(listOf("p.MINE"), backend.playlistsForSong("1").map { it.id })
        val detail = backend.detail("playlist", "p.MINE")!!
        assertTrue(detail.info.editable)
        assertEquals("Late", detail.info.editableDescription)
    }

    @Test fun answersComeInTheAppLanguageWhenTheStorefrontCarriesIt() {
        val dutchStore = listOf("nl", "en-GB")
        assertEquals("en-GB", AppleMusicClient.languageFor(dutchStore, java.util.Locale.US))
        assertEquals("nl", AppleMusicClient.languageFor(dutchStore, java.util.Locale.forLanguageTag("nl-NL")))
        assertNull(AppleMusicClient.languageFor(dutchStore, java.util.Locale.JAPAN))
        assertEquals("en-GB", AppleMusicClient.languageFor(listOf("en-US", "en-GB"), java.util.Locale.UK))
    }

    @Test fun theWebPlayersOwnTokenIsPickedFromTheBundle() {
        fun jwt(kid: String) = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"alg":"ES256","kid":"$kid"}""".toByteArray()) + ".eyJpc3MiOiJ4In0.c2ln"
        val script = """const a="${jwt("OTHER")}",b="${jwt("WebPlayKid")}";"""
        assertEquals(jwt("WebPlayKid"), AppleMusicClient.webPlayToken(script))
        assertEquals(jwt("OTHER"), AppleMusicClient.webPlayToken("""x="${jwt("OTHER")}""""))
        assertNull(AppleMusicClient.webPlayToken("no tokens here"))
    }
}
