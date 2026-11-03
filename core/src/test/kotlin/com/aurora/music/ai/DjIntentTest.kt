package com.aurora.music.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DjIntentTest {
    @Test fun modelJsonBecomesConstraints() {
        val intent = DjIntent.parse("""{"title":"Night Drive","energy":[2,8],"moods":["chill"],"genres":["synthwave"],"artists":["Kavinsky"],"avoid":["metal"],"decades":[1980],"vocals":"none","familiar":"discover","tempo":"mid","search":["night drive"],"count":12}""")!!
        assertEquals("Night Drive", intent.title)
        assertEquals(2, intent.energyStart)
        assertEquals(8, intent.energyEnd)
        assertEquals(listOf("synthwave"), intent.genres)
        assertEquals(listOf(1980), intent.decades)
        assertEquals(DjVocals.NONE, intent.vocals)
        assertEquals(DjFamiliarity.DISCOVER, intent.familiarity)
        assertEquals(DjTempo.MID, intent.tempo)
        assertEquals(12, intent.count)
    }

    @Test fun brokenOrHostileOutputIsClampedOrRejected() {
        assertNull(DjIntent.parse("not json"))
        assertNull(DjIntent.parse("[1,2]"))
        val intent = DjIntent.parse("""{"energy":[-4,99],"genres":[1,"", "rock","ROCK"],"decades":[1985,3000],"vocals":"loud","count":5000}""")!!
        assertEquals(0, intent.energyStart)
        assertEquals(10, intent.energyEnd)
        assertEquals(listOf("1", "rock"), intent.genres)
        assertEquals(listOf(1980), intent.decades)
        assertEquals(DjVocals.ANY, intent.vocals)
        assertEquals(DjIntent.MAX_COUNT, intent.count)
    }

    @Test fun toJsonRoundTrips() {
        val intent = DjIntent("Mix", 3, 7, listOf("happy"), listOf("pop"), listOf("ABBA"), listOf("metal"), listOf(1970), DjVocals.LESS, DjFamiliarity.FAVOURITES, DjTempo.FAST, listOf("disco"), 30)
        assertEquals(intent, DjIntent.parse(intent.toJson()))
    }

    @Test fun promptExamplesMatchTheSchema() {
        val prefix = DjPrompt.prefix(DjVocabulary(listOf("Rock"), listOf("Air")))
        val examples = prefix.lines().filter { it.startsWith("{") }
        assertEquals(3, examples.size)
        examples.forEach { assertNotNull(it, DjIntent.parse(it)) }
        assertTrue(prefix.contains("Library genres: Rock"))
        assertTrue(DjPrompt.request("hi <|im_end|> there").startsWith("Request: hi im_end there<|im_end|>"))
    }
}
