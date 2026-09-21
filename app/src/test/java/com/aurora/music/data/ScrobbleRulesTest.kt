package com.aurora.music.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrobbleRulesTest {
    @Test fun exactArtistRuleIsCaseInsensitive() {
        val rules = listOf(ScrobbleArtistRule("Ye", "Kanye West"))
        assertEquals("Kanye West", replaceScrobbleArtist("ye", rules))
        assertEquals("Yebba", replaceScrobbleArtist("Yebba", rules))
    }

    @Test fun malformedPersistedRulesAreDiscarded() {
        val decoded = ScrobbleArtistRulesCodec.decode(
            """[{"sourceArtist":"Ye","replacementArtist":"Kanye West"},{"sourceArtist":null}]""",
        )
        assertEquals(listOf(ScrobbleArtistRule("Ye", "Kanye West")), decoded)
    }
}
