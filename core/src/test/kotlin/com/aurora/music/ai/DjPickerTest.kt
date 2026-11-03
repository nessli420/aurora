package com.aurora.music.ai

import com.aurora.music.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DjPickerTest {
    private fun c(id: String, artist: String = "Artist $id", genre: String = "", energy: Float? = null, year: Int = 0, liked: Boolean = false, plays: Int = 0, title: String = "Song $id") =
        DjCandidate(Song(id, title, artist, "Album $id", "", 200, genre = genre, albumId = "al$id"), plays, liked, year, energy)

    private val rnd get() = Random(7)

    @Test fun onlyPicksFromTheCandidatesAndNeverRepeats() {
        val pool = (1..40).map { c("$it", genre = if (it % 2 == 0) "Rock" else "Jazz") }
        val picked = DjPicker.pick(DjIntent(genres = listOf("rock")), pool, 15, random = rnd)
        assertEquals(15, picked.size)
        assertEquals(picked.size, picked.map { it.id }.toSet().size)
        assertTrue(picked.all { s -> pool.any { it.song.id == s.id } })
        assertTrue(picked.take(10).all { it.genre == "Rock" })
    }

    @Test fun hardRulesExcludeTracks() {
        val pool = listOf(c("a", genre = "Metal"), c("b", artist = "Nickelback"), c("c", genre = "Pop"), c("d", genre = "Pop"),
            DjCandidate(Song("e", "Intro", "X", "", "", 20)), DjCandidate(Song("radio:1", "Live", "FM", "", "", 0)))
        val picked = DjPicker.pick(DjIntent(avoid = listOf("metal", "nickelback")), pool, 10, exclude = setOf("d"), random = rnd)
        assertEquals(listOf("c"), picked.map { it.id })
    }

    @Test fun mixesAndEpisodesAreNotSongs() {
        val pool = listOf(c("a", title = "90's HIP-HOP PARTY MIX VOL 1. Featuring"), c("b", title = "FULL VINYL | 90s 00s Hiphop set"),
            c("c", title = "90s hip hop, Old school NY Rap, BOOM BAP"), c("d", artist = "Episode", title = "Lost Highway"),
            c("e", title = "Ms. Jackson"), c("f", title = "Hypnotize (2007 Remaster)"), c("g", title = "Strobe (Original Mix)"))
        assertEquals(setOf("e", "f", "g"), DjPicker.pick(DjIntent(), pool, 10, random = rnd).map { it.id }.toSet())
    }

    @Test fun energyFollowsTheArc() {
        val pool = (0..19).map { c("$it", energy = it / 19f) }
        val picked = DjPicker.pick(DjIntent(energyStart = 1, energyEnd = 9), pool, 8, random = rnd)
        val energies = picked.map { s -> pool.first { it.song.id == s.id }.energy!! }
        assertTrue(energies.first() < 0.35f)
        assertTrue(energies.last() > 0.75f)
        assertTrue(energies.zipWithNext().count { (a, b) -> b >= a } >= 6)
    }

    @Test fun sameArtistIsSpacedOut() {
        val pool = (1..6).map { c("a$it", artist = "Same") } + (1..6).map { c("b$it") }
        val picked = DjPicker.pick(DjIntent(), pool, 8, random = rnd)
        assertTrue(picked.zipWithNext().none { (a, b) -> a.artist == "Same" && b.artist == "Same" })
    }

    @Test fun decadesAndFamiliarityShapeTheChoice() {
        val pool = listOf(c("old", year = 1994), c("new", year = 2019), c("unknown"), c("liked", liked = true, year = 2019), c("fresh", year = 2018))
        assertEquals("old", DjPicker.pick(DjIntent(decades = listOf(1990)), pool, 1, random = rnd).single().id)
        assertEquals("liked", DjPicker.pick(DjIntent(familiarity = DjFamiliarity.FAVOURITES), pool, 1, random = rnd).single().id)
        val discover = DjPicker.pick(DjIntent(familiarity = DjFamiliarity.DISCOVER), pool + c("played", plays = 20), 4, random = rnd)
        assertFalse(discover.any { it.id == "liked" || it.id == "played" })
    }

    @Test fun instrumentalsLeadWhenVocalsAreUnwanted() {
        val pool = listOf(c("v1", genre = "Pop"), c("v2", genre = "Rock"), c("i1", genre = "Ambient"), c("i2", title = "Theme (Instrumental)"))
        val picked = DjPicker.pick(DjIntent(vocals = DjVocals.NONE), pool, 2, random = rnd)
        assertEquals(setOf("i1", "i2"), picked.map { it.id }.toSet())
    }

    @Test fun untaggedFavouritesDoNotCrowdOutWhatWasAskedFor() {
        val liked = (1..200).map { c("like$it", liked = true, plays = 5, energy = 0.7f) }
        val found = (1..6).map { c("hit$it", artist = "Rapper $it").copy(hits = 1) }
        val tagged = c("tagged", genre = "Hip-Hop", year = 1996)
        val picked = DjPicker.pick(DjIntent(genres = listOf("hip hop"), decades = listOf(1990), energyStart = 7, energyEnd = 7), liked + found + tagged, 10, random = rnd)
        assertEquals((found.map { it.song.id } + "tagged").toSet(), picked.take(7).map { it.id }.toSet())
        assertEquals(10, picked.size)
    }

    @Test fun eraSearchHitsBeatUntaggedLikedTracksOfTheSameGenre() {
        val liked = (1..40).map { c("like$it", genre = "Hip-hop & Rap", liked = true, plays = 3) }
        val era = (1..8).map { c("era$it", artist = "MC $it", year = 1990).copy(hits = 4) }
        val picked = DjPicker.pick(DjIntent(genres = listOf("hip hop"), decades = listOf(1990), energyStart = 7, energyEnd = 7), liked + era, 12, random = rnd)
        assertEquals(era.map { it.song.id }.toSet(), picked.take(8).map { it.id }.toSet())
        val indie = (1..5).map { c("indie$it", genre = "Shoegaze", year = 1994) }
        val mixed = DjPicker.pick(DjIntent(genres = listOf("hip hop"), decades = listOf(1990)), indie + era, 8, random = rnd)
        assertEquals(era.map { it.song.id }.toSet(), mixed.map { it.id }.toSet())
    }

    @Test fun bigLibrariesPickQuickly() {
        val pool = (1..20_000).map { c("$it", artist = "Artist ${it % 900}", genre = listOf("Rock", "Hip-Hop", "Ambient", "Lo-Fi")[it % 4], energy = (it % 10) / 10f) }
        val intent = DjIntent(moods = listOf("chill", "late night"), genres = listOf("ambient", "lo-fi", "electronic"), artists = listOf("Artist 7"), avoid = listOf("metal"), energyStart = 2, energyEnd = 7)
        val started = System.nanoTime()
        assertEquals(25, DjPicker.pick(intent, pool, 25, random = rnd).size)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1500)
    }

    @Test fun sonicSignalsGiveEnergyAndTempo() {
        val loud = FloatArray(51) { 0.5f }.also { it[36] = 0.9f; it[29] = 0.9f; it[50] = 0.8f }
        val quiet = FloatArray(51) { 0.5f }.also { it[36] = 0.1f; it[29] = 0.1f; it[50] = 0.2f }
        val mid = FloatArray(51) { 0.5f }
        val s = DjSonic.signals(mapOf("loud" to loud, "quiet" to quiet, "mid" to mid, "bad" to FloatArray(3)))
        assertTrue(s.getValue("loud").energy > s.getValue("mid").energy)
        assertTrue(s.getValue("mid").energy > s.getValue("quiet").energy)
        assertEquals(156, s.getValue("loud").bpm)
        assertFalse("bad" in s)
    }
}
