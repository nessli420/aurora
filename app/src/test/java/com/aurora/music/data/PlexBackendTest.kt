package com.aurora.music.data

import com.aurora.music.data.remote.PlexClient
import com.aurora.music.data.remote.PlexException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class PlexBackendTest {
    private val token = "fixture-token+/=&?#"

    private class Fixture : AutoCloseable {
        val server = MockWebServer()
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        @Volatile var respond: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(404) }
        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    return respond(request)
                }
            }
            server.start()
        }
        val url get() = server.url("/").toString().trimEnd('/')
        override fun close() = server.close()
    }

    private fun session(fixture: Fixture) = Session(fixture.url, "Fixture", "", token, ServerType.PLEX, "machine-fixture")
    private fun backend(fixture: Fixture) = PlexBackend(PlexClient(session(fixture), OkHttpClient()), { 0 }, { it })
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun page(items: List<String>, total: Int = items.size, offset: Int = 0) =
        json("""{"MediaContainer":{"size":${items.size},"totalSize":$total,"offset":$offset,"Metadata":[${items.joinToString(",")}]}}""")
    private fun sections() = json("""{"MediaContainer":{"size":3,"Directory":[{"key":"1","title":"Movies","type":"movie"},{"key":"2","title":"Music","type":"artist"},{"key":"3","title":"More music","type":"artist"}]}}""")
    private fun root() = json("""{"MediaContainer":{"friendlyName":"Fixture Plex","machineIdentifier":"machine-fixture","version":"1.41.0"}}""")
    private fun offset(request: RecordedRequest) =
        (request.requestUrl!!.queryParameter("X-Plex-Container-Start") ?: request.getHeader("X-Plex-Container-Start") ?: "0").toInt()
    private fun track(id: String, title: String = "Track $id", extra: String = "") =
        """{"ratingKey":"$id","key":"/library/metadata/$id","type":"track","title":"$title","parentRatingKey":"20","parentTitle":"Album","grandparentRatingKey":"10","grandparentTitle":"Artist","duration":123456,"thumb":"/library/metadata/$id/thumb/123","Media":[{"container":"flac","audioCodec":"flac","bitrate":1400,"audioChannels":2,"Part":[{"id":"$id","key":"/library/parts/$id/file.flac","file":"/music/Track.flac","Stream":[{"streamType":2,"codec":"flac","samplingRate":96000,"bitDepth":24}]}]}]$extra}"""
    private fun stations(key: String) =
        """, "Stations":{"Metadata":[{"key":"$key","type":"playlist","playlistType":"audio","radio":true}]}"""

    private suspend fun cancelAfterRequestStarts(started: CompletableDeferred<Unit>, block: suspend () -> Unit) = coroutineScope {
        var returnedNormally = false
        val pending = async { block(); returnedNormally = true }
        try {
            withTimeout(10_000) { started.await() }
            pending.cancel()
            withTimeout(2_000) { pending.join() }
            assertFalse("The cancelled operation returned a normal result", returnedNormally)
            assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
        } finally {
            pending.cancel()
        }
    }

    @Test fun authenticatesAgainstTheServerWithTokenAndClientHeaders() = runBlocking {
        Fixture().use { fixture ->
            fixture.respond = { request -> when (request.requestUrl!!.encodedPath) {
                "/", "/identity" -> root()
                "/library/sections" -> sections()
                else -> MockResponse().setResponseCode(404)
            } }
            val result = PlexClient.authenticate(fixture.url, token)
            assertEquals(ServerType.PLEX, result.type)
            assertEquals(token, result.token)
            assertTrue(result.isValid)
            assertEquals("machine-fixture", result.userId)
            assertTrue(fixture.requests.isNotEmpty())
            fixture.requests.forEach { request ->
                assertEquals(token, request.getHeader("X-Plex-Token"))
                assertFalse(request.getHeader("X-Plex-Client-Identifier").isNullOrBlank())
                assertEquals("Aurora", request.getHeader("X-Plex-Product"))
            }
        }
    }

    @Test fun unauthorizedServerCannotProduceASession() = runBlocking {
        Fixture().use { fixture ->
            fixture.respond = { MockResponse().setResponseCode(401).setBody("Unauthorized") }
            val error = runCatching { PlexClient.authenticate(fixture.url, token) }.exceptionOrNull()
            assertNotNull("An invalid token must fail authentication", error)
            assertFalse(error?.message.orEmpty().contains(token))
        }
    }

    @Test fun serverRedirectsNeverForwardCredentialsToAnotherHost() = runBlocking {
        Fixture().use { origin -> Fixture().use { foreign ->
            origin.respond = { MockResponse().setResponseCode(302).setHeader("Location", foreign.url + "/") }
            foreign.respond = { root() }
            assertNotNull(runCatching { PlexClient.authenticate(origin.url, token) }.exceptionOrNull())
            assertEquals(0, foreign.server.requestCount)
        } }
    }

    @Test fun cancelledHttpRequestDoesNotHoldMergedSourceTimeoutOpen() = runBlocking {
        Fixture().use { fixture ->
            val started = CompletableDeferred<Unit>()
            fixture.respond = {
                started.complete(Unit)
                MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            }
            val client = PlexClient(session(fixture), OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build())
            cancelAfterRequestStarts(started) { client.serverInfo() }
            assertEquals(1, fixture.server.requestCount)
        }
    }

    @Test fun reportingPlexReadFailuresReturnsUiFallbacksAndPublishesErrors() = runBlocking {
        Fixture().use { fixture ->
            fixture.respond = { MockResponse().setResponseCode(503) }
            val errors = mutableListOf<String>()
            val source = ReportingMediaBackend(backend(fixture), errors::add)
            assertTrue(source.allPlaylists().isEmpty())
            assertNull(source.detail("playlist", "900"))
            assertEquals(2, errors.size)
            assertTrue(errors.all { it.contains("Plex") && !it.contains(token) })
        }
    }

    @Test fun reportingPlexCancellationDoesNotBecomeAnEmptyPlaylistResult() = runBlocking {
        Fixture().use { fixture ->
            val started = CompletableDeferred<Unit>()
            fixture.respond = {
                started.complete(Unit)
                MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            }
            val errors = mutableListOf<String>()
            val source = ReportingMediaBackend(backend(fixture), errors::add)
            cancelAfterRequestStarts(started) { source.allPlaylists() }
            assertTrue(errors.isEmpty())
        }
    }

    @Test fun reportingPlexCollectionsStillRejectIncompleteTracklists() = runBlocking {
        Fixture().use { fixture ->
            fixture.respond = { request -> if (offset(request) == 0) page(listOf(track("101")), 2)
                else page(emptyList(), 2, 1) }
            val errors = mutableListOf<String>()
            val source = ReportingMediaBackend(backend(fixture), errors::add)
            assertNotNull(runCatching { source.collectionTracks("playlist", "900") }.exceptionOrNull())
            assertTrue(errors.isEmpty())
            assertEquals(listOf(0, 1), fixture.requests.map(::offset))
        }
    }

    @Test fun authenticationRejectsServersWithoutAnAccessibleMusicSection() = runBlocking {
        Fixture().use { fixture ->
            fixture.respond = { request -> if (request.requestUrl!!.encodedPath == "/library/sections")
                json("""{"MediaContainer":{"Directory":[{"key":"1","title":"Movies","type":"movie"}]}}""") else root() }
            assertNotNull(runCatching { PlexClient.authenticate(fixture.url, token) }.exceptionOrNull())
        }
    }

    @Test fun plexAccountIdentityIgnoresFriendlyNameAndSeparatesAccessTokens() {
        val first = Session("https://plex.invalid", "Living room", "", "first-token", ServerType.PLEX, "machine")
        assertEquals(first.accountKey(), first.copy(username = "Renamed server").accountKey())
        assertNotEquals(first.accountKey(), first.copy(token = "second-token").accountKey())
        assertFalse(first.accountKey().contains(first.token))
    }

    @Test fun readsEveryMusicSectionAndHonoursServerCappedPages() = runBlocking {
        Fixture().use { fixture ->
            fixture.respond = { request -> when (request.requestUrl!!.encodedPath) {
                "/library/sections" -> sections()
                "/library/sections/2/all" -> if (offset(request) == 0) page(listOf(track("101"), track("102")), 3)
                    else page(listOf(track("103")), 3, 2)
                "/library/sections/3/all" -> page(listOf(track("201")))
                else -> MockResponse().setResponseCode(404)
            } }
            val songs = backend(fixture).allSongs()
            assertEquals(listOf("101", "102", "103", "201"), songs.map { it.id })
            assertEquals(listOf(0, 2), fixture.requests.filter { it.requestUrl!!.encodedPath == "/library/sections/2/all" }.map(::offset))
            assertFalse(fixture.requests.any { it.requestUrl!!.encodedPath.startsWith("/library/sections/1/") })
            assertTrue(fixture.requests.filter { it.requestUrl!!.encodedPath.endsWith("/all") }
                .all { it.requestUrl!!.queryParameter("type") == "10" })
            assertEquals(123, songs.first().durationSec)
            assertEquals(96000, songs.first().sampleRateHz)
            assertEquals(24, songs.first().bitDepth)
            assertEquals("20", songs.first().albumId)
            assertEquals("10", songs.first().artistId)
        }
    }

    @Test fun sparseMetadataDoesNotCrashOrInventTechnicalInformation() = runBlocking {
        Fixture().use { fixture ->
            fixture.respond = { page(listOf("""{"ratingKey":"101","type":"track","title":"Sparse","duration":null,"Media":null,"Genre":null}""")) }
            val song = requireNotNull(backend(fixture).songFor("101"))
            assertEquals("101", song.id)
            assertEquals("Sparse", song.title)
            assertEquals(0, song.durationSec)
            assertEquals(0, song.sampleRateHz)
            assertEquals(0, song.bitDepth)
            assertEquals("", song.albumId)
            assertEquals("", song.artistId)
        }
    }

    @Test fun originalsAndArtworkEncodeTokensWithoutChangingTheirHost() = runBlocking {
        Fixture().use { fixture ->
            fixture.respond = { page(listOf(track("101"))) }
            val source = backend(fixture)
            val song = requireNotNull(source.songFor("101"))
            val original = source.streamUrl(song.id, 64, true).toHttpUrl()
            assertEquals(fixture.server.port, original.port)
            assertEquals("/library/parts/101/file.flac", original.encodedPath)
            assertEquals(token, original.queryParameter("X-Plex-Token"))
            assertEquals(1, original.queryParameterValues("X-Plex-Token").size)
            assertFalse(original.encodedPath.contains("transcode"))
            val cover = song.artworkUrl.toHttpUrl()
            assertEquals(fixture.server.port, cover.port)
            assertEquals(token, cover.queryParameter("X-Plex-Token"))
        }
    }

    @Test fun bitrateCapsRequestMp3AndFreshDownloadResolverHydratesOriginalPart() = runBlocking {
        Fixture().use { fixture ->
            fixture.respond = { page(listOf(track("101"))) }
            val fresh = backend(fixture)
            val original = fresh.downloadUrl("101", 0, true).toHttpUrl()
            assertEquals("/library/parts/101/file.flac", original.encodedPath)
            assertTrue(fixture.requests.any { it.requestUrl!!.encodedPath == "/library/metadata/101" })
            val capped = fresh.streamUrl("101", 192, false).toHttpUrl()
            assertEquals("/music/:/transcode/universal/start.mp3", capped.encodedPath)
            assertEquals("/library/metadata/101", capped.queryParameter("path"))
            assertEquals("192", capped.queryParameter("musicBitrate"))
            assertEquals("0", capped.queryParameter("directPlay"))
            assertEquals("0", capped.queryParameter("directStream"))
            assertEquals(token, capped.queryParameter("X-Plex-Token"))
        }
    }

    @Test fun fourStarAndFiveStarRatingsMapToLikes() = runBlocking {
        Fixture().use { fixture ->
            fixture.respond = { request ->
                val id = request.requestUrl!!.pathSegments.last()
                page(listOf(track(id, extra = ",\"userRating\":$id")))
            }
            val source = backend(fixture)
            assertFalse(requireNotNull(source.songFor("0")).liked)
            assertFalse(requireNotNull(source.songFor("7")).liked)
            assertTrue(requireNotNull(source.songFor("8")).liked)
            assertTrue(requireNotNull(source.songFor("10")).liked)
        }
    }

    @Test fun foreignMediaAndArtworkUrlsNeverReceiveTheServerToken() = runBlocking {
        Fixture().use { fixture ->
            fixture.respond = { page(listOf(track("101")
                .replace("/library/parts/101/file.flac", "https://foreign.invalid/private.flac")
                .replace("/library/metadata/101/thumb/123", "//foreign.invalid/private.jpg"))) }
            val source = backend(fixture)
            val song = requireNotNull(source.songFor("101"))
            listOf(song.streamUrl, song.artworkUrl, source.streamUrl(song.id, 0, true)).filter { it.isNotBlank() }.forEach { url ->
                assertEquals("Only same-server URLs may carry Plex credentials", fixture.server.port, url.toHttpUrl().port)
                assertFalse(url.contains("foreign.invalid"))
            }
        }
    }

    @Test fun audioPlaylistsKeepRepeatedOccurrencesAndRemoveEveryMatchingEntryId() = runBlocking {
        Fixture().use { fixture ->
            fixture.respond = { request -> when {
                request.method == "DELETE" -> MockResponse().setResponseCode(200)
                request.requestUrl!!.encodedPath == "/playlists/900/items" -> if (offset(request) == 0)
                    page(listOf(track("101", extra = ",\"playlistItemID\":701"), track("102", extra = ",\"playlistItemID\":702")), 3)
                    else page(listOf(track("101", extra = ",\"playlistItemID\":703")), 3, 2)
                request.requestUrl!!.encodedPath == "/playlists/900" -> page(listOf("""{"ratingKey":"900","type":"playlist","playlistType":"audio","title":"Repeated","leafCount":3}"""))
                else -> MockResponse().setResponseCode(404)
            } }
            val source = backend(fixture)
            assertEquals(listOf("101", "102", "101"), source.collectionTracks("playlist", "900").map { it.id })
            assertTrue(source.removeFromPlaylist("900", listOf("101")))
            assertEquals(setOf("/playlists/900/items/701", "/playlists/900/items/703"),
                fixture.requests.filter { it.method == "DELETE" }.map { it.requestUrl!!.encodedPath }.toSet())
        }
    }

    @Test fun missingOccurrenceIdsAndIncompleteListsNeverStartPlaylistRemoval() = runBlocking {
        Fixture().use { fixture ->
            fixture.respond = { request -> if (request.requestUrl!!.encodedPath == "/playlists/900")
                page(listOf("""{"ratingKey":"900","type":"playlist","playlistType":"audio","title":"Music","leafCount":1}"""))
                else page(listOf(track("101"))) }
            assertFalse(backend(fixture).removeFromPlaylist("900", listOf("101")))
            assertFalse(fixture.requests.any { it.method == "DELETE" })
        }
        Fixture().use { fixture ->
            fixture.respond = { request -> if (request.requestUrl!!.encodedPath == "/playlists/900")
                page(listOf("""{"ratingKey":"900","type":"playlist","playlistType":"audio","title":"Music","leafCount":2}"""))
                else if (offset(request) == 0)
                page(listOf(track("101", extra = ",\"playlistItemID\":701")), 2)
                else MockResponse().setResponseCode(503) }
            val result = runCatching { backend(fixture).removeFromPlaylist("900", listOf("101")) }
            assertFalse(result.getOrDefault(false))
            assertFalse(fixture.requests.any { it.method == "DELETE" })
        }
    }

    @Test fun invalidTrackIdsAreRejectedBeforeAnyPlaylistChunkIsAdded() = runBlocking {
        Fixture().use { fixture ->
            fixture.respond = { request -> if (request.method == "GET")
                page(listOf("""{"ratingKey":"900","type":"playlist","playlistType":"audio","title":"Music"}"""))
                else MockResponse().setResponseCode(200) }
            val ids = (1..100).map { it.toString() } + "../101"
            assertFalse(backend(fixture).addToPlaylist("900", ids))
            assertFalse(fixture.requests.any { it.method != "GET" })
        }
    }

    @Test fun invalidOccurrenceIdsAreRejectedBeforeAnyPlaylistEntryIsRemoved() = runBlocking {
        Fixture().use { fixture ->
            fixture.respond = { request -> when {
                request.method == "DELETE" -> MockResponse().setResponseCode(200)
                request.requestUrl!!.encodedPath == "/playlists/900" ->
                    page(listOf("""{"ratingKey":"900","type":"playlist","playlistType":"audio","title":"Music","leafCount":2}"""))
                else -> page(listOf(
                    track("101", extra = ",\"playlistItemID\":701"),
                    track("101", extra = ",\"playlistItemID\":\"../702\""),
                ))
            } }
            assertFalse(backend(fixture).removeFromPlaylist("900", listOf("101")))
            assertFalse(fixture.requests.any { it.method == "DELETE" })
        }
    }

    @Test fun truncatedLaterPageCannotEraseTheEarlierAdvertisedCollectionSize() = runBlocking {
        Fixture().use { fixture ->
            fixture.respond = { request -> when {
                request.method == "DELETE" -> MockResponse().setResponseCode(200)
                request.requestUrl!!.encodedPath == "/playlists/900" ->
                    page(listOf("""{"ratingKey":"900","type":"playlist","playlistType":"audio","title":"Music","leafCount":3}"""))
                offset(request) == 0 -> page(listOf(track("101", extra = ",\"playlistItemID\":701")), 3)
                else -> json("""{"MediaContainer":{"size":0,"offset":1,"Metadata":[]}}""")
            } }
            assertNotNull(runCatching { backend(fixture).collectionTracks("playlist", "900") }.exceptionOrNull())
            assertFalse(backend(fixture).removeFromPlaylist("900", listOf("101")))
            assertFalse(fixture.requests.any { it.method == "DELETE" })
        }
    }

    @Test fun totalSizeResponseHeaderKeepsServerCappedPagesComplete() = runBlocking {
        Fixture().use { fixture ->
            fixture.respond = { request ->
                val start = offset(request)
                json("""{"MediaContainer":{"size":1,"Metadata":[${track((101 + start).toString())}]}}""")
                    .setHeader("X-Plex-Container-Start", start)
                    .setHeader("X-Plex-Container-Total-Size", 3)
            }
            val tracks = backend(fixture).collectionTracks("album", "20")
            assertEquals(listOf("101", "102", "103"), tracks.map { it.id })
            assertEquals(listOf(0, 1, 2), fixture.requests.map(::offset))
        }
    }

    @Test fun laterCappedPagesDoNotNeedToRepeatTheCollectionTotal() = runBlocking {
        Fixture().use { fixture ->
            fixture.respond = { request ->
                val start = offset(request)
                if (start == 0) page(listOf(track("101")), 3)
                else json("""{"MediaContainer":{"size":1,"offset":$start,"Metadata":[${track((101 + start).toString())}]}}""")
            }
            assertEquals(listOf("101", "102", "103"), backend(fixture).collectionTracks("album", "20").map { it.id })
            assertEquals(listOf(0, 1, 2), fixture.requests.map(::offset))
        }
    }

    @Test fun libraryPagingCrossesSectionBoundariesWithoutSkippingTracks() = runBlocking {
        Fixture().use { fixture ->
            fixture.respond = { request -> when (request.requestUrl!!.encodedPath) {
                "/library/sections" -> sections()
                "/library/sections/2/all", "/library/sections/3/all" -> {
                    val start = offset(request)
                    val count = request.requestUrl!!.queryParameter("X-Plex-Container-Size")!!.toInt()
                    val base = if (request.requestUrl!!.encodedPath.contains("/2/")) 100 else 200
                    page((1..3).map { track((base + it).toString()) }.drop(start).take(count), 3, start)
                }
                else -> MockResponse().setResponseCode(404)
            } }
            val source = backend(fixture)
            assertEquals(listOf("103", "201", "202"), source.songsPage(2, 3).map { it.id })
            assertEquals(listOf(2), fixture.requests.filter { it.requestUrl!!.encodedPath == "/library/sections/2/all" }.map(::offset))
            fixture.requests.clear()
            assertEquals(listOf("203"), source.songsPage(5, 3).map { it.id })
            assertFalse(fixture.requests.filter { it.requestUrl!!.encodedPath.endsWith("/all") }.any { offset(it) == 0 })
            assertTrue(source.songsPage(6, 3).isEmpty())
            fixture.requests.clear()
            assertTrue(source.songsPage(-1, 3).isEmpty())
            assertTrue(source.songsPage(0, 0).isEmpty())
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test fun pagingWithoutSectionTotalsStillCrossesSectionBoundariesCorrectly() = runBlocking {
        Fixture().use { fixture ->
            fixture.respond = { request -> when (request.requestUrl!!.encodedPath) {
                "/library/sections" -> sections()
                "/library/sections/2/all", "/library/sections/3/all" -> {
                    val start = offset(request)
                    val count = request.requestUrl!!.queryParameter("X-Plex-Container-Size")!!.toInt()
                    val base = if (request.requestUrl!!.encodedPath.contains("/2/")) 100 else 200
                    val rows = (1..3).map { track((base + it).toString()) }.drop(start).take(count)
                    json("""{"MediaContainer":{"size":${rows.size},"offset":$start,"Metadata":[${rows.joinToString(",")}]}}""")
                }
                else -> MockResponse().setResponseCode(404)
            } }
            val source = backend(fixture)
            assertEquals(listOf("103", "201", "202"), source.songsPage(2, 3).map { it.id })
            assertEquals(listOf("203"), source.songsPage(5, 3).map { it.id })
            assertTrue(source.songsPage(6, 3).isEmpty())
        }
    }

    @Test fun playlistAdditionKeepsDuplicatesAndEncodesNativeSourceUris() = runBlocking {
        Fixture().use { fixture ->
            fixture.respond = { request -> if (request.method == "GET")
                page(listOf("""{"ratingKey":"900","type":"playlist","playlistType":"audio","title":"Music"}"""))
                else MockResponse().setResponseCode(200) }
            val ids = (1..100).map { it.toString() } + listOf("101", "1")
            assertTrue(backend(fixture).addToPlaylist("900", ids))
            val additions = fixture.requests.filter { it.method == "PUT" }
            assertEquals(2, additions.size)
            assertTrue(additions.all { it.requestUrl!!.encodedPath == "/playlists/900/items" })
            val prefix = "server://machine-fixture/com.plexapp.plugins.library/library/metadata/"
            assertEquals(prefix + ids.take(100).joinToString(","), additions[0].requestUrl!!.queryParameter("uri"))
            assertEquals(prefix + "101,1", additions[1].requestUrl!!.queryParameter("uri"))
            additions.forEach { assertEquals(token, it.getHeader("X-Plex-Token")) }
        }
    }

    @Test fun failedPlaylistChunkStopsLaterWritesAndReturnsFailure() = runBlocking {
        Fixture().use { fixture ->
            fixture.respond = { request -> if (request.method == "GET")
                page(listOf("""{"ratingKey":"900","type":"playlist","playlistType":"audio","title":"Music"}"""))
                else MockResponse().setResponseCode(403) }
            assertFalse(backend(fixture).addToPlaylist("900", (1..201).map { it.toString() }))
            assertEquals(1, fixture.requests.count { it.method == "PUT" })
        }
    }

    @Test fun cancelledPlaylistMutationPropagatesCancellationWithoutTryingMoreChunks() = runBlocking {
        Fixture().use { fixture ->
            val started = CompletableDeferred<Unit>()
            fixture.respond = { request -> if (request.method == "GET")
                page(listOf("""{"ratingKey":"900","type":"playlist","playlistType":"audio","title":"Music"}"""))
                else {
                    started.complete(Unit)
                    MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                } }
            cancelAfterRequestStarts(started) { backend(fixture).addToPlaylist("900", (1..201).map { it.toString() }) }
            assertEquals(1, fixture.requests.count { it.method == "PUT" })
        }
    }

    @Test fun smartPlaylistsCannotBeEditedAsStaticLists() = runBlocking {
        Fixture().use { fixture ->
            fixture.respond = { page(listOf("""{"ratingKey":"900","type":"playlist","playlistType":"audio","title":"Smart","smart":true}""")) }
            val source = backend(fixture)
            assertFalse(source.addToPlaylist("900", listOf("101")))
            assertFalse(source.removeFromPlaylist("900", listOf("101")))
            assertTrue(fixture.requests.all { it.method == "GET" })
        }
    }

    @Test fun playlistListingExcludesVideoAndPhotoCollections() = runBlocking {
        Fixture().use { fixture ->
            fixture.respond = { page(listOf(
                """{"ratingKey":"1","type":"playlist","playlistType":"audio","title":"Music","leafCount":2}""",
                """{"ratingKey":"2","type":"playlist","playlistType":"video","title":"Movies"}""",
                """{"ratingKey":"3","type":"playlist","playlistType":"photo","title":"Photos"}"""
            )) }
            assertEquals(listOf("1"), backend(fixture).allPlaylists().map { it.id })
        }
    }

    @Test fun addToPlaylistChoicesExcludeSmartCollectionsButKeepThemBrowsable() = runBlocking {
        Fixture().use { fixture ->
            fixture.respond = { page(listOf(
                """{"ratingKey":"1","type":"playlist","playlistType":"audio","title":"Static","smart":false}""",
                """{"ratingKey":"2","type":"playlist","playlistType":"audio","title":"Smart boolean","smart":true}""",
                """{"ratingKey":"3","type":"playlist","playlistType":"audio","title":"Smart integer","smart":1}""",
                """{"ratingKey":"4","type":"playlist","playlistType":"audio","title":"Static integer","smart":0}""",
                """{"ratingKey":"5","type":"playlist","playlistType":"video","title":"Movies","smart":false}""",
            )) }
            val source = backend(fixture)
            assertEquals(listOf("1", "2", "3", "4"), source.allPlaylists().map { it.id })
            assertEquals(listOf("1", "4"), source.playlistsForSong("101").map { it.id })
        }
    }

    @Test fun playlistDescriptionEditingNeverUsesTheGeneratedTrackCountSubtitle() = runBlocking {
        Fixture().use { fixture ->
            var summary: String? = null
            fixture.respond = { request -> when (request.requestUrl!!.encodedPath) {
                "/playlists/900" -> {
                    val description = summary?.let { ",\"summary\":\"$it\"" }.orEmpty()
                    page(listOf("""{"ratingKey":"900","type":"playlist","playlistType":"audio","title":"Music","leafCount":1$description}"""))
                }
                "/playlists/900/items" -> page(listOf(track("101")))
                else -> MockResponse().setResponseCode(404)
            } }
            val source = backend(fixture)
            val withoutDescription = requireNotNull(source.detail("playlist", "900"))
            assertEquals("", withoutDescription.info.editableDescription)
            assertTrue(withoutDescription.info.subtitle.contains("1"))
            assertTrue(withoutDescription.info.subtitle.contains("song"))
            summary = "Original / mix & notes"
            val withDescription = requireNotNull(source.detail("playlist", "900"))
            assertEquals(summary, withDescription.info.editableDescription)
            assertEquals(summary, withDescription.info.subtitle)
        }
    }

    @Test fun likedListingUsesPlexInclusiveRatingFilterAndChecksReturnedRatings() = runBlocking {
        Fixture().use { fixture ->
            fixture.respond = { request -> if (request.requestUrl!!.encodedPath == "/library/sections")
                json("""{"MediaContainer":{"Directory":[{"key":"2","type":"artist","title":"Music"}]}}""")
                else page(listOf(
                    track("101", extra = ",\"userRating\":7"),
                    track("102", extra = ",\"userRating\":8"),
                    track("103", extra = ",\"userRating\":10"),
                )) }
            val source = backend(fixture)
            assertEquals(listOf("102", "103"), source.starredSongs().map { it.id })
            assertEquals(2, source.starredCount())
            assertEquals(setOf("102"), source.likedSongIds(listOf("101", "102", "missing")))
            val queries = fixture.requests.filter { it.requestUrl!!.encodedPath.endsWith("/all") }
            assertTrue(queries.isNotEmpty())
            assertTrue(queries.all { it.requestUrl!!.queryParameter("userRating>") == "8" })
            assertTrue(queries.all { it.requestUrl!!.queryParameter("type") == "10" })
        }
    }

    @Test fun ratingWritesAndScrobblesUseNativeKeysAndReportHttpFailure() = runBlocking {
        Fixture().use { fixture ->
            fixture.respond = { MockResponse().setResponseCode(200) }
            val source = backend(fixture)
            assertTrue(source.setStarred("101", true, "song"))
            assertTrue(source.setStarred("101", false, "song"))
            source.scrobble("101")
            val ratings = fixture.requests.filter { it.requestUrl!!.encodedPath == "/:/rate" }
            assertEquals(listOf("10", "0"), ratings.map { it.requestUrl!!.queryParameter("rating") })
            assertTrue(ratings.all { it.requestUrl!!.queryParameter("key") == "101" })
            assertEquals("101", fixture.requests.single { it.requestUrl!!.encodedPath == "/:/scrobble" }.requestUrl!!.queryParameter("key"))
            fixture.respond = { MockResponse().setResponseCode(403) }
            assertFalse(source.setStarred("101", true, "song"))
        }
    }

    @Test fun radioUsesTheAdvertisedStationAndPreservesTheInitialQueueWindow() = runBlocking {
        Fixture().use { fixture ->
            val station = "/library/metadata/101/station/opaque-key?type=10"
            fixture.respond = { request -> when (request.requestUrl!!.encodedPath) {
                "/library/metadata/101" -> page(listOf(track("101", extra = stations(station))))
                "/playQueues" -> page(listOf(track("101"), track("103"), track("103"), track("102"),
                    """{"ratingKey":"104","type":"track","title":"Unavailable","Media":[]}""", track("105")), 100, 90)
                else -> MockResponse().setResponseCode(404)
            } }
            val songs = backend(fixture).radio("101")
            assertEquals(listOf("103", "102", "105"), songs.map { it.id })
            assertTrue(songs.all { it.streamUrl.isNotBlank() })
            val metadata = fixture.requests.single { it.requestUrl!!.encodedPath == "/library/metadata/101" }
            assertEquals("1", metadata.requestUrl!!.queryParameter("includeStations"))
            val queue = fixture.requests.single { it.requestUrl!!.encodedPath == "/playQueues" }
            assertEquals("POST", queue.method)
            assertEquals("audio", queue.requestUrl!!.queryParameter("type"))
            assertEquals("server://machine-fixture/com.plexapp.plugins.library$station", queue.requestUrl!!.queryParameter("uri"))
            assertFalse(fixture.requests.any { it.requestUrl!!.encodedPath.endsWith("/nearest") })
        }
    }

    @Test fun radioUsesSonicNeighboursWhenNoTrackStationIsAdvertised() = runBlocking {
        Fixture().use { fixture ->
            fixture.respond = { request -> when (request.requestUrl!!.encodedPath) {
                "/library/metadata/101" -> page(listOf(track("101")))
                "/library/metadata/101/nearest" -> page(listOf(track("101"), track("103"), track("102"), track("103")))
                else -> MockResponse().setResponseCode(404)
            } }
            assertEquals(listOf("103", "102"), backend(fixture).radio("101").map { it.id })
            val neighbours = fixture.requests.single { it.requestUrl!!.encodedPath.endsWith("/nearest") }
            assertEquals("31", neighbours.requestUrl!!.queryParameter("limit"))
            assertFalse(fixture.requests.any { it.method != "GET" })
            assertFalse(fixture.requests.any { it.requestUrl!!.encodedPath.endsWith("/allLeaves") })
        }
    }

    @Test fun radioCanFallBackToTheArtistsAdvertisedStation() = runBlocking {
        Fixture().use { fixture ->
            val station = "/library/metadata/10/station/artist-key?type=10"
            fixture.respond = { request -> when (request.requestUrl!!.encodedPath) {
                "/library/metadata/101" -> page(listOf(track("101")))
                "/library/metadata/101/nearest" -> MockResponse().setResponseCode(404)
                "/library/metadata/10" -> page(listOf("""{"ratingKey":"10","type":"artist","title":"Artist"${stations(station)}}"""))
                "/playQueues" -> page(listOf(track("102"), track("201", title = "Related artist")))
                else -> MockResponse().setResponseCode(404)
            } }
            assertEquals(listOf("102", "201"), backend(fixture).radio("101").map { it.id })
            val artist = fixture.requests.single { it.requestUrl!!.encodedPath == "/library/metadata/10" }
            assertEquals("1", artist.requestUrl!!.queryParameter("includeStations"))
            val queue = fixture.requests.single { it.requestUrl!!.encodedPath == "/playQueues" }
            assertEquals("server://machine-fixture/com.plexapp.plugins.library$station", queue.requestUrl!!.queryParameter("uri"))
        }
    }

    @Test fun unsupportedStationFallsBackToSonicNeighbours() = runBlocking {
        Fixture().use { fixture ->
            fixture.respond = { request -> when (request.requestUrl!!.encodedPath) {
                "/library/metadata/101" -> page(listOf(track("101", extra = stations("/library/metadata/101/station/disabled"))))
                "/playQueues" -> MockResponse().setResponseCode(403)
                "/library/metadata/101/nearest" -> page(listOf(track("102")))
                else -> MockResponse().setResponseCode(404)
            } }
            assertEquals(listOf("102"), backend(fixture).radio("101").map { it.id })
            assertEquals(1, fixture.requests.count { it.method == "POST" })
        }
    }

    @Test fun radioDoesNotHideAuthenticationOrServerFailuresBehindRandomTracks() = runBlocking {
        for (status in listOf(401, 503)) Fixture().use { fixture ->
            fixture.respond = { request -> when (request.requestUrl!!.encodedPath) {
                "/library/metadata/101" -> page(listOf(track("101", extra = stations("/library/metadata/101/station/native"))))
                "/playQueues" -> MockResponse().setResponseCode(status)
                else -> page(listOf(track("102")))
            } }
            assertNotNull(runCatching { backend(fixture).radio("101") }.exceptionOrNull())
            assertEquals(listOf("/library/metadata/101", "/playQueues"), fixture.requests.map { it.requestUrl!!.encodedPath })
        }
    }

    @Test fun cancellingNativeRadioNeverStartsItsFallbacks() = runBlocking {
        Fixture().use { fixture ->
            val started = CompletableDeferred<Unit>()
            fixture.respond = { request -> when (request.requestUrl!!.encodedPath) {
                "/library/metadata/101" -> page(listOf(track("101", extra = stations("/library/metadata/101/station/native"))))
                "/playQueues" -> {
                    started.complete(Unit)
                    MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                }
                else -> page(listOf(track("102")))
            } }
            cancelAfterRequestStarts(started) { backend(fixture).radio("101") }
            assertEquals(listOf("/library/metadata/101", "/playQueues"), fixture.requests.map { it.requestUrl!!.encodedPath })
        }
    }

    @Test fun radioNeverFollowsForeignStationUrls() = runBlocking {
        Fixture().use { fixture -> Fixture().use { foreign ->
            foreign.respond = { page(listOf(track("999"))) }
            fixture.respond = { request -> when (request.requestUrl!!.encodedPath) {
                "/library/metadata/101" -> page(listOf(track("101", extra = stations(foreign.url + "/station"))))
                "/library/metadata/101/nearest" -> page(listOf(track("102")))
                else -> MockResponse().setResponseCode(404)
            } }
            assertEquals(listOf("102"), backend(fixture).radio("101").map { it.id })
            assertEquals(0, foreign.server.requestCount)
            assertFalse(fixture.requests.any { it.requestUrl!!.encodedPath == "/playQueues" })
        } }
    }

    @Test fun unavailableRadioFallsBackToBoundedServerRandomTracksAcrossSections() = runBlocking {
        Fixture().use { fixture ->
            fixture.respond = { request -> when (request.requestUrl!!.encodedPath) {
                "/library/metadata/101" -> page(listOf(track("101")))
                "/library/metadata/101/nearest" -> MockResponse().setResponseCode(404)
                "/library/metadata/10" -> page(listOf("""{"ratingKey":"10","type":"artist","title":"Artist"}"""))
                "/library/sections" -> sections()
                "/library/sections/2/all" -> page(listOf(track("101"), track("102")))
                "/library/sections/3/all" -> page(listOf(track("201"), track("202")))
                else -> MockResponse().setResponseCode(404)
            } }
            assertEquals(setOf("102", "201", "202"), backend(fixture).radio("101").map { it.id }.toSet())
            val queries = fixture.requests.filter { it.requestUrl!!.encodedPath.endsWith("/all") }
            assertEquals(2, queries.size)
            assertTrue(queries.all { it.requestUrl!!.queryParameter("sort") == "random" })
            assertTrue(queries.all { it.requestUrl!!.queryParameter("type") == "10" })
            assertTrue(queries.all { it.requestUrl!!.queryParameter("X-Plex-Container-Size")!!.toInt() <= 200 })
        }
    }

    @Test fun plexHomeRecommendationsSurviveMergedLibraryWrapping() = runBlocking {
        Fixture().use { fixture ->
            fixture.respond = { request -> when (request.requestUrl!!.encodedPath) {
                "/library/sections" -> json("""{"MediaContainer":{"Directory":[{"key":"2","type":"artist","title":"Music"}]}}""")
                "/hubs/sections/2" -> json("""{"MediaContainer":{"Hub":[
                    {"hubIdentifier":"music.recommended","title":"Made for you","type":"track","Metadata":[${track("101")}]},
                    {"hubIdentifier":"music.albums","title":"Rediscover","type":"album","Metadata":[{"ratingKey":"20","type":"album","title":"Rediscovered","parentTitle":"Artist","leafCount":8}]},
                    {"hubIdentifier":"music.artists","title":"Similar artists","type":"artist","Metadata":[{"ratingKey":"10","type":"artist","title":"Artist"}]}
                ]}}""")
                else -> page(emptyList())
            } }
            val source = backend(fixture)
            val home = source.home()
            assertEquals(listOf("Made for you", "Rediscover", "Similar artists"), home.sections.map { it.title })
            assertEquals("101", (home.sections[0].items.single() as HomeFeedItem.Track).song.id)
            assertEquals("20", (home.sections[1].items.single() as HomeFeedItem.Record).album.id)
            assertEquals("10", (home.sections[2].items.single() as HomeFeedItem.Performer).artist.id)
            val merged = MergedBackend(listOf(source), source.session)
            val mergedHome = merged.home()
            assertEquals(home.sections.map { it.title }, mergedHome.sections.map { it.title })
            val mergedSong = (mergedHome.sections[0].items.single() as HomeFeedItem.Track).song
            assertNotEquals("101", mergedSong.id)
            assertEquals("101", stripMergeNamespace(mergedSong.id))
            assertEquals("20", stripMergeNamespace((mergedHome.sections[1].items.single() as HomeFeedItem.Record).album.id))
            assertEquals("10", stripMergeNamespace((mergedHome.sections[2].items.single() as HomeFeedItem.Performer).artist.id))
            assertEquals(fixture.server.port, merged.streamUrl(mergedSong.id, 0, true).toHttpUrl().port)
        }
    }

    @Test fun failedSupplementaryHubsPreserveCoreHomeShelvesAndOtherSections() = runBlocking {
        val failures = listOf<() -> MockResponse>(
            { MockResponse().setResponseCode(503) },
            { json("not plex json") },
        )
        for (failure in failures) Fixture().use { fixture ->
            fixture.respond = { request -> when (request.requestUrl!!.encodedPath) {
                "/library/sections" -> sections()
                "/hubs/sections/2" -> failure()
                "/hubs/sections/3" -> json("""{"MediaContainer":{"Hub":[{"hubIdentifier":"music.recommended","title":"Made for you","type":"track","Metadata":[${track("201")}]}]}}""")
                "/playlists" -> page(listOf("""{"ratingKey":"900","type":"playlist","playlistType":"audio","title":"Music","leafCount":1}"""))
                "/library/sections/2/all", "/library/sections/3/all" -> {
                    val first = request.requestUrl!!.encodedPath.contains("/2/")
                    when (request.requestUrl!!.queryParameter("type")) {
                        "9" -> {
                            val id = if (first) "20" else "30"
                            page(listOf("""{"ratingKey":"$id","type":"album","title":"Album $id","parentTitle":"Artist","leafCount":1}"""))
                        }
                        "10" -> page(listOf(track(if (first) "101" else "201", extra = ",\"userRating\":10")))
                        else -> page(emptyList())
                    }
                }
                else -> MockResponse().setResponseCode(404)
            } }
            val home = backend(fixture).home()
            assertEquals(setOf("20", "30"), home.newReleases.map { it.id }.toSet())
            assertEquals(listOf("900"), home.playlists.map { it.id })
            assertEquals(setOf("101", "201"), home.starred.map { it.id }.toSet())
            assertEquals(listOf("Made for you"), home.sections.map { it.title })
            assertEquals("201", (home.sections.single().items.single() as HomeFeedItem.Track).song.id)
        }
    }

    @Test fun homeRecommendationAuthenticationFailuresRemainVisible() = runBlocking {
        Fixture().use { fixture ->
            fixture.respond = { request -> when (request.requestUrl!!.encodedPath) {
                "/library/sections" -> json("""{"MediaContainer":{"Directory":[{"key":"2","type":"artist","title":"Music"}]}}""")
                "/hubs/sections/2" -> MockResponse().setResponseCode(401)
                else -> page(emptyList())
            } }
            val error = runCatching { backend(fixture).home() }.exceptionOrNull()
            assertTrue(error is PlexException)
            assertEquals(401, (error as PlexException).statusCode)
        }
    }

    @Test fun stalledRecommendationHubCannotTimeOutTheWholeMergedPlexHome() = runBlocking {
        Fixture().use { fixture ->
            fixture.respond = { request -> when {
                request.requestUrl!!.encodedPath == "/library/sections" -> sections()
                request.requestUrl!!.encodedPath == "/hubs/sections/2" -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                request.requestUrl!!.encodedPath == "/hubs/sections/3" -> json("""{"MediaContainer":{"Hub":[{"hubIdentifier":"music.recommended","title":"Made for you","type":"track","Metadata":[${track("201")}]}]}}""")
                request.requestUrl!!.encodedPath == "/playlists" -> page(listOf("""{"ratingKey":"900","type":"playlist","playlistType":"audio","title":"Music","leafCount":1}"""))
                request.requestUrl!!.encodedPath.endsWith("/all") && request.requestUrl!!.queryParameter("type") == "9" ->
                    page(listOf("""{"ratingKey":"20","type":"album","title":"Still here","parentTitle":"Artist","leafCount":1}"""))
                else -> page(emptyList())
            } }
            val source = backend(fixture)
            val home = withTimeout(7_000) { MergedBackend(listOf(source), source.session).home() }
            assertEquals(listOf("Still here"), home.newReleases.map { it.title })
            assertEquals(listOf("Music"), home.playlists.map { it.title })
            assertEquals(listOf("Made for you"), home.sections.map { it.title })
        }
    }

    @Test fun cancellingHomeRecommendationsDoesNotReturnAPartialSuccess() = runBlocking {
        Fixture().use { fixture ->
            val started = CompletableDeferred<Unit>()
            fixture.respond = { request -> when (request.requestUrl!!.encodedPath) {
                "/library/sections" -> json("""{"MediaContainer":{"Directory":[{"key":"2","type":"artist","title":"Music"}]}}""")
                "/hubs/sections/2" -> {
                    started.complete(Unit)
                    MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                }
                else -> page(emptyList())
            } }
            cancelAfterRequestStarts(started) { backend(fixture).home() }
            assertTrue(fixture.requests.any { it.requestUrl!!.encodedPath == "/hubs/sections/2" })
        }
    }

    @Test fun mergedHomeRecommendationSectionKeysCannotCollideAcrossPlexServers() = runBlocking {
        Fixture().use { first -> Fixture().use { second ->
            val respond: (RecordedRequest) -> MockResponse = { request -> when (request.requestUrl!!.encodedPath) {
                "/library/sections" -> json("""{"MediaContainer":{"Directory":[{"key":"2","type":"artist","title":"Music"}]}}""")
                "/hubs/sections/2" -> json("""{"MediaContainer":{"Hub":[{"hubIdentifier":"music.recommended","title":"Made for you","type":"track","Metadata":[${track("101")}]}]}}""")
                else -> page(emptyList())
            } }
            first.respond = respond
            second.respond = respond
            val source = backend(first)
            val home = MergedBackend(listOf(source, backend(second)), source.session).home()
            assertEquals(2, home.sections.size)
            assertEquals(2, home.sections.map { it.id }.toSet().size)
            assertEquals(2, home.sections.flatMap { it.items }.map { (it as HomeFeedItem.Track).song.id }.toSet().size)
        } }
    }

    @Test fun mergedPlexIdsSurviveSourceReorderingAndCannotRouteToMissingAccounts() = runBlocking {
        Fixture().use { first -> Fixture().use { second ->
            fun install(fixture: Fixture, title: String) {
                fixture.respond = { request -> when (request.requestUrl!!.encodedPath) {
                    "/library/sections" -> json("""{"MediaContainer":{"Directory":[{"key":"2","type":"artist","title":"Music"}]}}""")
                    "/library/sections/2/all", "/library/metadata/101" -> page(listOf(track("101", title)))
                    "/:/rate" -> MockResponse().setResponseCode(200)
                    else -> MockResponse().setResponseCode(404)
                } }
            }
            install(first, "First server recording")
            install(second, "Second server recording")
            val a = backend(first); val b = backend(second)
            val merged = MergedBackend(listOf(a, b), a.session)
            val songs = merged.allSongs()
            assertEquals(2, songs.size)
            assertTrue(ServerType.PLEX.supportsMergedLibrary)
            val original = songs.single { it.title == "Second server recording" }
            assertNotEquals(songs[0].id, songs[1].id)
            val reordered = MergedBackend(listOf(b, a), a.session)
            assertEquals(original.id, requireNotNull(reordered.songFor(original.id)).id)
            assertEquals(original.playbackSource, reordered.songFor(original.id)?.playbackSource)
            assertTrue(reordered.setStarred(original.id, true, "song"))
            assertFalse(first.requests.any { it.requestUrl!!.encodedPath == "/:/rate" })
            assertEquals(1, second.requests.count { it.requestUrl!!.encodedPath == "/:/rate" })
            assertEquals(second.server.port, reordered.streamUrl(original.id, 0, true).toHttpUrl().port)
            val removed = MergedBackend(listOf(a), a.session)
            assertNull(removed.songFor(original.id))
            assertEquals("", removed.streamUrl(original.id, 0, true))
            assertFalse(removed.setStarred(original.id, false, "song"))
            second.respond = { MockResponse().setResponseCode(503) }
            assertEquals(listOf("First server recording"), merged.allSongs().map { it.title })
        } }
    }

    @Test fun mergedPlaylistAdditionRejectsTheWholeRequestWhenAnyTrackCannotRouteToItsSource() = runBlocking {
        Fixture().use { first -> Fixture().use { second ->
            fun install(fixture: Fixture) {
                fixture.respond = { request -> if (request.method == "GET")
                    page(listOf("""{"ratingKey":"900","type":"playlist","playlistType":"audio","title":"Music","smart":false}"""))
                    else MockResponse().setResponseCode(200) }
            }
            install(first)
            install(second)
            val a = backend(first)
            val merged = MergedBackend(listOf(a, backend(second)), a.session)
            val separator = MERGE_NAMESPACE_SEP
            val playlistId = "0${separator}900"
            val ownTrack = "0${separator}101"
            for (unroutable in listOf("1${separator}102", "missing", "999${separator}103", "0${separator}../104")) {
                assertFalse(merged.addToPlaylist(playlistId, listOf(ownTrack, unroutable)))
                assertFalse(first.requests.any { it.method != "GET" })
                assertFalse(second.requests.any { it.method != "GET" })
            }
            assertTrue(merged.addToPlaylist(playlistId, listOf(ownTrack, ownTrack)))
            val addition = first.requests.single { it.method == "PUT" }
            assertEquals("server://machine-fixture/com.plexapp.plugins.library/library/metadata/101,101",
                addition.requestUrl!!.queryParameter("uri"))
            assertTrue(second.requests.isEmpty())
        } }
    }
}
