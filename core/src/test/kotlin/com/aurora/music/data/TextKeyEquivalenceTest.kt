package com.aurora.music.data

import com.aurora.music.data.artwork.ArtworkUrls
import com.aurora.music.data.artwork.artworkKey
import com.aurora.music.model.Song
import com.aurora.music.util.TrackMatch
import com.aurora.music.util.lettersAndNumbers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer
import java.util.Locale
import java.util.Random

class TextKeyEquivalenceTest {
    private val marks = Regex("\\p{M}+")
    private val separators = Regex("[^\\p{L}\\p{N}]+")
    private val bracketed = Regex("\\s*[\\[(](?:feat\\.?|ft\\.?|featuring)\\s+[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE)
    private val trailing = Regex("\\s+(?:feat\\.?|ft\\.?|featuring)\\s+.*$", RegexOption.IGNORE_CASE)
    private val feature = Regex("\\((feat|ft)\\.?[^)]*\\)")

    private fun slowKey(value: String, form: Normalizer.Form) = Normalizer.normalize(value, form).replace(marks, "")
        .lowercase(Locale.ROOT).replace(separators, " ").trim()

    private fun slowTitle(title: String) = title.replace(bracketed, "").replace(trailing, "").trim()

    private fun slowNorm(s: String): String {
        val folded = buildString {
            for (c in Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFKC)) append(
                if (c.code >= 0x80 && Character.UnicodeScript.of(c.code) == Character.UnicodeScript.LATIN) Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0] else c
            )
        }
        return folded.replace(feature, " ").replace(separators, " ").trim()
    }

    private val samples = listOf(
        "", " ", "   Plain Title  ", "AC/DC", "Don't Stop -- Believin'!", "!!!", "…", "R&B, Soul; Funk",
        "Café Lights", "Beyoncé", "São Paulo", "Straße", "ǅ", "İstanbul", "ΣΑΣ Σ", "áb", "Ａｂｃ（feat. Ｘ）", "ﬁn", "x²", "Ⅷ",
        "夏の雨", "かき", "がき", "한글 노래", "Ночь", "العربية", "ก้า", "𝒜𝒝", "😀 smile", "a\uD800b", "\uDC00",
        "Song (feat. Someone)", "Song [FT. Someone] Live", "Song ft. Someone", "Song featuring A & B", "Soft Left", "After (Ft)", "Feat",
        "a feat. b\nc", "Song (featuring", "(fab) day", "Song (Feat.Someone)", "℡ (FEAT x)",
    )

    private val tokens = listOf(
        "a", "B", "z9", "soft", "FT", "ft.", "feat", "Feat.", "featuring", " ", "  ", "\t", "\n", "(", ")", "[", "]", "-", "'", "&", ",", ";",
        "é", "É", "ß", "ı", "İ", "Σ", "σ", "ς", "か", "゙", "夏", "한", "Я", "²", "ﬁ", "Ⅷ", "（", "́", "ः", "​", "𝒜", "\uD800", "€", "™",
    )

    private fun fuzz(): List<String> {
        val random = Random(42)
        return List(20_000) { (0..random.nextInt(8)).joinToString("") { tokens[random.nextInt(tokens.size)] } }
    }

    @Test fun keysMatchTheRegexFormulas() {
        for (s in samples + fuzz()) {
            assertEquals(s, slowKey(s, Normalizer.Form.NFKD), artworkKey(s))
            assertEquals(s, slowTitle(s), recordingTitle(s))
            assertEquals(s, slowKey(slowTitle(s), Normalizer.Form.NFD), recordingKey(s))
            assertEquals(s, slowNorm(s), TrackMatch.norm(s))
        }
    }

    @Test fun everyCodePointIsKeptOrSplitLikeTheRegex() {
        for (cp in 0..Character.MAX_CODE_POINT) {
            val s = "a" + String(Character.toChars(cp)) + "b"
            assertEquals(separators.replace(s, " ").trim(), lettersAndNumbers(s))
        }
    }

    @Test fun serverArtAnswersStayExactOnceAServerIsKnown() {
        val known = "https://art.test:4533/music/rest/getCoverArt.view?"
        assertTrue(ArtworkUrls.isServerArt(known + "id=first&size=600"))
        assertTrue(ArtworkUrls.isServerArt(known + "id=al-2_0&u=a+b&v=1.16.1"))
        assertTrue(ArtworkUrls.isServerArt(known + "id=a%20b"))
        assertTrue(ArtworkUrls.isServerArt(known))
        assertFalse(ArtworkUrls.isServerArt(known + "id=a b"))
        assertFalse(ArtworkUrls.isServerArt(known + "id=%zz"))
        assertFalse(ArtworkUrls.isServerArt("https://art.test:4533/music/rest/stream.view?id=1"))
        assertFalse(ArtworkUrls.isServerArt("https://user@art.test:4533/music/rest/getCoverArt.view?id=1"))
        assertFalse(ArtworkUrls.isServerArt("https://under_score/rest/getCoverArt.view?id=1"))
        assertTrue(ArtworkUrls.isServerArt("http://other.test/rest/getCoverArt.view?id=1"))
    }

    @Test fun localArtistIdsKeepTheirHashAndArtistsStaySorted() {
        val index = LocalArtistIndex(listOf(Song("one", "Title", "SEWERSLVT, abc, Zed", "Album", "", 100)), ArtistSeparators())
        assertEquals(listOf("abc", "SEWERSLVT", "Zed"), index.artists.map { it.name })
        assertEquals("local-artist-e91775bf75fd8cc3ca928a75298f5df566f39ab7467551dcdfd468c9d5d0ec0e", index.artists[1].id)
    }
}
