package com.aurora.music.util

import java.text.Normalizer
import java.util.Locale

object TrackMatch {
    // live cuts and extended mixes differ by more than this same recording rarely does
    const val DURATION_TOLERANCE_SEC = 4

    private val FEATURE = Regex("\\((feat|ft)\\.?[^)]*\\)")

    // accents fold only on latin letters; other scripts keep every letter and mark so different titles stay apart
    fun norm(s: String): String {
        val lower = s.lowercase(Locale.ROOT)
        // ascii is already nfkc and folds to itself
        val folded = if (lower.all { it.code < 0x80 }) lower else buildString(lower.length) {
            for (c in Normalizer.normalize(lower, Normalizer.Form.NFKC)) append(fold(c))
        }
        return lettersAndNumbers(if ("(f" in folded) folded.replace(FEATURE, " ") else folded)
    }

    private val folds = CharArray(0x250)

    private fun fold(c: Char): Char {
        if (c.code < 0x80) return c
        if (c.code < folds.size && folds[c.code] != '\u0000') return folds[c.code]
        val out = if (Character.UnicodeScript.of(c.code) == Character.UnicodeScript.LATIN) Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0] else c
        if (c.code < folds.size) folds[c.code] = out
        return out
    }

    // names made only of symbols would otherwise all share an empty key
    fun identity(s: String): String = norm(s).ifEmpty { s.trim().lowercase(Locale.ROOT) }

    fun key(artist: String, title: String): String = identity(artist) + "|" + identity(title)
}

private val MARKS = Regex("\\p{M}+")

// ascii has no marks and every normal form leaves it alone
internal fun markFreeKey(value: String, form: Normalizer.Form): String {
    val text = if (value.all { it.code < 0x80 }) value else Normalizer.normalize(value, form).replace(MARKS, "")
    return lettersAndNumbers(text.lowercase(Locale.ROOT))
}

// same as replacing [^\p{L}\p{N}]+ with a space and trimming, without a native matcher per call
internal fun lettersAndNumbers(s: String): String {
    val out = StringBuilder(s.length)
    var gap = false
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        if (isLetterOrNumber(cp)) {
            if (gap && out.isNotEmpty()) out.append(' ')
            gap = false
            out.appendCodePoint(cp)
        } else gap = true
        i += Character.charCount(cp)
    }
    return out.toString()
}

private fun isLetterOrNumber(cp: Int): Boolean {
    if (cp < 0x80) return cp in 'a'.code..'z'.code || cp in 'A'.code..'Z'.code || cp in '0'.code..'9'.code
    return when (Character.getType(cp).toByte()) {
        Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER, Character.MODIFIER_LETTER, Character.OTHER_LETTER,
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
        else -> false
    }
}
