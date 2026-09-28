package com.aurora.music.data

import com.aurora.music.model.Song
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class LyricsRepositoryTest {
    private val song = Song("song", "FRIDAY", "First, Second & Third", "Album", "", 180)
    private fun result(artist: String, title: String = "friday", synced: Boolean = true) = Gson().toJson(mapOf(
        "trackName" to title, "artistName" to artist, "duration" to 180,
        "syncedLyrics" to if (synced) "[00:01.00]Found lyrics" else null,
        "plainLyrics" to "Plain lyrics",
    ))

    private fun repository(
        calls: MutableList<HttpUrl>, separators: ArtistSeparators = ArtistSeparators(),
        backend: MediaBackend? = null, enabled: Boolean = true,
        respond: (HttpUrl) -> String? = { null },
    ): LyricsRepository {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            calls += request.url
            val body = respond(request.url)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (body != null || request.url.encodedPath.endsWith("search")) 200 else 404).message("Fixture")
                .body((body ?: if (request.url.encodedPath.endsWith("search")) "[]" else "{}").toResponseBody()).build()
        }.build()
        return LyricsRepository({ backend }, { enabled }, { separators }, http)
    }
    private fun List<HttpUrl>.getArtists() = filter { it.encodedPath.endsWith("get") }.map { it.queryParameter("artist_name") }

    @Test fun fullCreditMatchAvoidsAllFallbackRequests() = runBlocking {
        val calls = mutableListOf<HttpUrl>()
        val lyrics = repository(calls) { result(song.artist) }.lyricsFor(song)
        assertTrue(lyrics!!.synced)
        assertEquals(1, calls.size)
        assertEquals(listOf(song.artist), calls.getArtists())
    }

    @Test fun failedFullCreditSearchTriesPrimaryThenEveryCollaboratorAndStopsOnMatch() = runBlocking {
        val calls = mutableListOf<HttpUrl>()
        val lyrics = repository(calls) { url ->
            if (url.queryParameter("artist_name") == "Second" && url.encodedPath.endsWith("search"))
                "[${result("sEcOnD")}]" else null
        }.lyricsFor(song)
        assertEquals("Found lyrics", lyrics!!.lines.single().text)
        assertEquals(listOf(song.artist, "First", "Second"), calls.getArtists())
        assertEquals(listOf(song.artist, "First", "Second"), calls.filter { it.queryParameter("track_name") != null }
            .map { it.queryParameter("artist_name") }.distinct())
        assertTrue(calls.filter { it.queryParameter("track_name") != null }.all { it.queryParameter("track_name") == song.title })
        assertTrue(calls.none { it.queryParameter("artist_name") == "Third" })
    }

    @Test fun lastArtistIsTriedWhenOthersHaveNoLyrics() = runBlocking {
        val calls = mutableListOf<HttpUrl>()
        val lyrics = repository(calls) { url ->
            if (url.queryParameter("artist_name") == "Third") result("Third") else null
        }.lyricsFor(song)
        assertNotNull(lyrics)
        assertEquals(listOf(song.artist, "First", "Second", "Third"), calls.getArtists())
    }

    @Test fun mixedSeparatorsAndDuplicateCreditsPreserveOrder() = runBlocking {
        val credit = "Lead, Guest; Other / Singer & Rapper feat. Feature ft Friend featuring Last + Extra | Final x Duo × End, LEAD"
        val calls = mutableListOf<HttpUrl>()
        assertNull(repository(calls).lyricsFor(song.copy(artist = credit)))
        assertEquals(listOf(credit, "Lead", "Guest", "Other", "Singer", "Rapper", "Feature", "Friend", "Last", "Extra", "Final", "Duo", "End"), calls.getArtists())
    }

    @Test fun bandNamePunctuationAndDisabledSeparatorsArePreserved() = runBlocking {
        for (credit in listOf("AC/DC", "R&B", "Within Temptation")) {
            val calls = mutableListOf<HttpUrl>()
            repository(calls).lyricsFor(song.copy(artist = credit))
            assertEquals(listOf(credit), calls.getArtists())
        }
        val rules = ArtistSeparators(ArtistSeparators.defaults().map {
            if (it.text in listOf(",", "&")) it.copy(match = SeparatorMatch.OFF) else it
        } + ArtistSeparator("vs.", SeparatorMatch.WORD))
        val calls = mutableListOf<HttpUrl>()
        repository(calls, rules).lyricsFor(song.copy(artist = "Earth, Wind & Fire vs. Guest"))
        assertEquals(listOf("Earth, Wind & Fire vs. Guest", "Earth, Wind & Fire", "Guest"), calls.getArtists())
    }

    @Test fun wrongTitlesAndSubstringArtistsAreRejectedBeforeTryingNextArtist() = runBlocking {
        val calls = mutableListOf<HttpUrl>()
        val lyrics = repository(calls) { url ->
            when {
                url.encodedPath.endsWith("search") -> "[${result("First", "Wrong song")},${result("First Aid Kit")}]"
                url.queryParameter("artist_name") == "Second" -> result("Second")
                else -> null
            }
        }.lyricsFor(song)
        assertNotNull(lyrics)
        assertEquals(listOf(song.artist, "First", "Second"), calls.getArtists())
    }

    @Test fun unicodeTitlesAndArtistsDoNotCollapseToEmptyMatches() = runBlocking {
        val calls = mutableListOf<HttpUrl>()
        val lyrics = repository(calls) { url ->
            when {
                url.encodedPath.endsWith("search") -> "[${result("Другой", "НОЧЬ")},${result("Первый", "ДЕНЬ")}]"
                url.queryParameter("artist_name") == "Второй" -> result("вТоРоЙ", "Ночь")
                else -> null
            }
        }.lyricsFor(song.copy(title = "НОЧЬ", artist = "Первый, Второй"))
        assertNotNull(lyrics)
        assertEquals(listOf("Первый, Второй", "Первый", "Второй"), calls.getArtists())
    }

    @Test fun plainLyricsRemainAvailableButSyncedCollaboratorLyricsWin() = runBlocking {
        for (withSynced in listOf(false, true)) {
            val calls = mutableListOf<HttpUrl>()
            val lyrics = repository(calls) { url ->
                when {
                    !url.encodedPath.endsWith("get") -> null
                    url.queryParameter("artist_name") == song.artist -> result(song.artist, synced = false)
                    withSynced && url.queryParameter("artist_name") == "Second" -> result("Second")
                    else -> null
                }
            }.lyricsFor(song)
            assertEquals(withSynced, lyrics!!.synced)
        }
    }

    @Test fun cleanedTitleIsRetriedForSecondaryArtist() = runBlocking {
        val calls = mutableListOf<HttpUrl>()
        val lyrics = repository(calls) { url ->
            if (url.queryParameter("artist_name") == "Second" && url.queryParameter("track_name") == "FRIDAY") result("Second") else null
        }.lyricsFor(song.copy(title = "FRIDAY (Remastered)"))
        assertNotNull(lyrics)
        assertTrue(calls.any { it.queryParameter("artist_name") == "Second" && it.queryParameter("track_name") == "FRIDAY (Remastered)" })
    }

    @Test fun diacriticsDoNotRejectTheSameArtistAndTrack() = runBlocking {
        val calls = mutableListOf<HttpUrl>()
        val lyrics = repository(calls) { url ->
            if (url.queryParameter("artist_name") == "Beyoncé") result("BEYONCE", "Deja Vu") else null
        }.lyricsFor(song.copy(title = "DÉJÀ VU", artist = "Beyoncé, Guest"))
        assertNotNull(lyrics)
        assertEquals(listOf("Beyoncé, Guest", "Beyoncé"), calls.getArtists())
    }

    @Test fun disablingFeatureSeparatorDoesNotReintroduceItsUndottedAlias() = runBlocking {
        val rules = ArtistSeparators(ArtistSeparators.defaults().map {
            if (it.text == "feat.") it.copy(match = SeparatorMatch.OFF) else it
        })
        val calls = mutableListOf<HttpUrl>()
        repository(calls, rules).lyricsFor(song.copy(artist = "Lead feat. Guest"))
        assertEquals(listOf("Lead feat. Guest"), calls.getArtists())
    }

    @Test fun disabledLookupAndSyncedServerLyricsSkipLrcLib() = runBlocking {
        val calls = mutableListOf<HttpUrl>()
        assertNull(repository(calls, enabled = false).lyricsFor(song))
        val server = Lyrics(listOf(com.aurora.music.model.LyricLine(0, "Server lyrics")), true, "Server")
        val backend = Proxy.newProxyInstance(MediaBackend::class.java.classLoader, arrayOf(MediaBackend::class.java)) { _, method, _ ->
            check(method.name == "serverLyrics"); server
        } as MediaBackend
        assertEquals(server, repository(calls, backend = backend).lyricsFor(song))
        assertTrue(calls.isEmpty())
    }

    @Test fun cancellationDoesNotContinueThroughArtistFallbacks() = runBlocking {
        val calls = mutableListOf<HttpUrl>()
        try {
            repository(calls) { throw CancellationException("Track changed") }.lyricsFor(song)
            fail("Cancellation swallowed")
        } catch (_: CancellationException) { }
        assertEquals(1, calls.size)
    }
}
