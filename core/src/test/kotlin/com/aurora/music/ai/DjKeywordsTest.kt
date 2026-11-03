package com.aurora.music.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DjKeywordsTest {
    private val library = DjVocabulary(listOf("Trip Hop", "Rock", "Ambient"), listOf("Massive Attack", "Boards of Canada", "Air"))

    @Test fun chillSetThatBuildsWithoutVocals() {
        val p = DjKeywords.parse("chill late-night set, no vocals, build energy at the end", library)
        val i = p.intent
        assertEquals(DjVocals.NONE, i.vocals)
        assertTrue(i.energyEnd - i.energyStart >= 4)
        assertTrue(i.energyStart <= 3)
        assertTrue("chill" in i.moods)
        assertTrue(i.genres.isEmpty())
        assertEquals(listOf("late night chill"), i.search)
        assertTrue(p.found.containsAll(listOf("vocals", "energy", "moods")))
    }

    @Test fun decadesGenresAndLength() {
        val i = DjKeywords.parse("80s and 90s rock for an hour", library).intent
        assertEquals(listOf(1980, 1990), i.decades)
        assertEquals("Rock", i.genres.first())
        assertEquals(15, i.count)
        assertEquals(listOf(2000), DjKeywords.parse("2000's pop", library).intent.decades)
        assertEquals(20, DjKeywords.parse("20 songs of nineties hip-hop", library).intent.count)
        assertEquals(listOf(1990), DjKeywords.parse("20 songs of nineties hip-hop", library).intent.decades)
        assertTrue("hip hop" in DjKeywords.parse("20 songs of nineties hip-hop", library).intent.genres)
    }

    @Test fun libraryArtistsAndOutsideArtistsBecomeSearches() {
        val i = DjKeywords.parse("something like Massive Attack and Portishead", library).intent
        assertEquals(listOf("Massive Attack"), i.artists)
        assertEquals(listOf("portishead"), i.search)
        assertEquals(listOf("bonobo"), DjKeywords.parse("something like bonobo but no jazz", library).intent.search)
        assertFalse(DjKeywords.parse("fresh air please", library).intent.artists.contains("Air"))
    }

    @Test fun avoidsWhatTheUserRulesOut() {
        val i = DjKeywords.parse("upbeat party mix, no rock, without Massive Attack", library).intent
        assertTrue("Rock" in i.avoid)
        assertTrue("Massive Attack" in i.avoid)
        assertFalse(i.genres.any { DjKeywords.norm(it) == "rock" })
        assertTrue(i.energyStart >= 7)
        assertEquals(DjFamiliarity.DISCOVER, DjKeywords.parse("discover something new", library).intent.familiarity)
        assertEquals(DjFamiliarity.FAVOURITES, DjKeywords.parse("my favourites", library).intent.familiarity)
    }

    @Test fun steeringChangesOnlyWhatWasAsked() {
        val base = DjIntent("Study", 3, 3, listOf("calm"), listOf("ambient"), vocals = DjVocals.NONE, count = 15)
        val up = DjKeywords.parse("more upbeat", library, base).intent
        assertEquals(5, up.energyStart)
        assertEquals(5, up.energyEnd)
        assertEquals(DjVocals.NONE, up.vocals)
        assertEquals(listOf("ambient"), up.genres)
        val era = DjKeywords.parse("switch to 90s", library, base).intent
        assertEquals(listOf(1990), era.decades)
        assertEquals(3, era.energyEnd)
        val genre = DjKeywords.parse("switch to trip hop", library, base).intent
        assertEquals(listOf("Trip Hop"), genre.genres)
        val down = DjKeywords.parse("calmer please", library, up).intent
        assertEquals(3, down.energyEnd)
    }

    @Test fun explicitWordsOverrideTheModel() {
        val request = "chill late-night set, no vocals, build energy at the end, 90s"
        val model = DjIntent(title = "Late Night", energyStart = 5, energyEnd = 5, genres = listOf("pop", "rock"), vocals = DjVocals.ANY)
        val merged = DjKeywords.merge(model.copy(decades = listOf(1980), avoid = listOf("ambient"), count = 15), DjKeywords.parse(request, library), request)
        assertEquals("Late Night", merged.title)
        assertEquals(DjVocals.NONE, merged.vocals)
        assertTrue(merged.energyEnd > merged.energyStart)
        assertEquals(listOf(1990), merged.decades)
        assertTrue(merged.genres.containsAll(listOf("pop", "rock")))
        assertTrue(merged.avoid.isEmpty())
        assertEquals(DjIntent.DEFAULT_COUNT, merged.count)
        val avoid = DjKeywords.merge(DjIntent(genres = listOf("rock", "jazz")), DjKeywords.parse("jazz but no rock", library), "jazz but no rock")
        assertEquals(listOf("jazz"), avoid.genres.map { DjKeywords.norm(it) }.distinct())
        val base = DjIntent("Night", 3, 3, genres = listOf("ambient"))
        val steered = DjKeywords.merge(DjIntent(title = "90s Chill", energyStart = 9, energyEnd = 9, genres = listOf("ambient")), DjKeywords.parse("switch to 90s", library, base), "switch to 90s", steering = true)
        assertEquals(3, steered.energyEnd)
        assertEquals(listOf(1990), steered.decades)
        val party = DjIntent("90s Party", 7, 7, genres = listOf("Hip Hop"), decades = listOf(1990), search = listOf("90s hip hop party"))
        val chill = DjKeywords.merge(DjIntent(title = "90s Chill", genres = listOf("ambient", "electronic"), search = listOf("90s chill")),
            DjKeywords.parse("more chill", library, party), "more chill", steering = true)
        assertEquals(listOf("Hip Hop"), chill.genres)
        assertEquals(listOf("90s hip hop party"), chill.search)
        assertTrue(chill.energyEnd < 7)
        assertEquals(null, DjPrompt.scrub(DjIntent(title = "Brighter Study")))
        assertEquals(listOf("gym"), DjPrompt.scrub(DjIntent(title = "Gym", search = listOf("80s workout rock", "gym")))!!.search)
    }
}
