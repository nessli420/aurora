package com.aurora.music.data

import com.aurora.music.model.Song
import org.junit.Assert.*
import org.junit.Test

class LocalArtistIndexTest {
    @Test fun defaultDelimitersIndexEveryCollaboratorWithoutChangingDisplay() {
        val credit = "Cynthoni, Sewerslvt; Guest / Singer & Rapper feat. Feature ft. Other with Last"
        val song = song("collab", credit)
        val index = LocalArtistIndex(listOf(song), ArtistSeparators())
        assertEquals(setOf("Cynthoni", "Sewerslvt", "Guest", "Singer", "Rapper", "Feature", "Other", "Last"), index.artists.map { it.name }.toSet())
        index.artists.forEach { assertEquals(listOf("collab"), index.songsBy(it.id).map { it.id }) }
        assertEquals(credit, index.songs.single().artist)
        assertEquals(song.albumId, index.songs.single().albumId)
        assertEquals(song.streamUrl, index.songs.single().streamUrl)
    }

    @Test fun soloAndCollaborationTracksShareAStableArtistCategory() {
        val collab = song("both", "Cynthoni, Sewerslvt")
        val solo = song("solo", "Sewerslvt").copy(artistId = "11")
        val first = LocalArtistIndex(listOf(collab, solo), ArtistSeparators())
        val artist = first.artists.single { it.name == "Sewerslvt" }
        assertEquals(listOf("both", "solo"), first.songsBy(artist.id).map { it.id })
        val rescanned = LocalArtistIndex(listOf(solo.copy(artist = "SEWERSLVT"), collab), ArtistSeparators())
        assertEquals(artist.id, rescanned.artists.single { it.name == "SEWERSLVT" }.id)
        assertEquals(artist.id, first.artist("11")?.id)
    }

    @Test fun defaultSpacingPreservesBandNamesAndWordFragments() {
        listOf("AC/DC", "R&B", "Within Temptation", "Withered").forEach {
            assertEquals(listOf(it), ArtistSeparators().split(it))
        }
        assertEquals(listOf("A", "B"), ArtistSeparators().split("A / B"))
    }

    @Test fun everyMatchModeAndCustomLiteralAreConfigurable() {
        fun split(mode: SeparatorMatch, text: String) = ArtistSeparators(listOf(ArtistSeparator("/", mode))).split(text)
        assertEquals(listOf("A/B"), split(SeparatorMatch.OFF, "A/B"))
        assertEquals(listOf("A/B"), split(SeparatorMatch.SPACED, "A/B"))
        assertEquals(listOf("A", "B"), split(SeparatorMatch.ANYWHERE, "A/B"))
        assertEquals(listOf("A", "B"), ArtistSeparators(listOf(ArtistSeparator(".*", SeparatorMatch.SPACED))).split("A .* B"))
        assertEquals(listOf("A", "B"), ArtistSeparators(listOf(ArtistSeparator("WITH", SeparatorMatch.WORD))).split("A with B"))
        assertEquals(listOf("Within"), ArtistSeparators(listOf(ArtistSeparator("with", SeparatorMatch.WORD))).split("Within"))
    }

    @Test fun repeatedCreditsDoNotDuplicateTracksOrUseFuzzyMatching() {
        val index = LocalArtistIndex(listOf(song("one", "Beyoncé; BEYONCÉ; Beyonce; ACDC; AC/DC")), ArtistSeparators())
        assertEquals(4, index.artists.size)
        index.artists.forEach { assertEquals(1, index.songsBy(it.id).size) }
    }

    @Test fun disablingTheCommaRebuildsCombinedMembershipWithoutChangingTheTrack() {
        val original = song("one", "Earth, Wind & Fire")
        val joined = ArtistSeparators(ArtistSeparators.defaults().map {
            if (it.text in listOf(",", "&")) it.copy(match = SeparatorMatch.OFF) else it
        })
        val index = LocalArtistIndex(listOf(original), joined)
        assertEquals("Earth, Wind & Fire", index.artists.single().name)
        assertEquals(original.artist, index.songs.single().artist)
    }

    @Test fun separatorSettingsRoundTripAndRejectInvalidRules() {
        val rules = ArtistSeparators(listOf(ArtistSeparator("vs.", SeparatorMatch.WORD), ArtistSeparator(";", SeparatorMatch.OFF)))
        assertEquals(rules, ArtistSeparatorsCodec.decode(ArtistSeparatorsCodec.encode(rules)))
        assertEquals(ArtistSeparators(), ArtistSeparatorsCodec.decode("{}"))
        assertEquals(listOf("A, B"), ArtistSeparatorsCodec.decode("{\"rules\":[]}").split("A, B"))
        listOf("{\"rules\":[{\"text\":\"\",\"match\":\"ANYWHERE\"}]}",
            "{\"rules\":[{\"text\":\";\",\"match\":\"UNKNOWN\"}]}").forEach { json ->
            assertTrue(runCatching { ArtistSeparatorsCodec.decode(json) }.isFailure)
        }
        assertTrue(runCatching { ArtistSeparatorsCodec.encode(ArtistSeparators(List(25) { ArtistSeparator("$it") })) }.isFailure)
    }

    private fun song(id: String, artist: String) = Song(id, "Title", artist, "Album", "", 100,
        artistId = "10", albumId = "20", streamUrl = "content://media/external/audio/media/$id")
}
