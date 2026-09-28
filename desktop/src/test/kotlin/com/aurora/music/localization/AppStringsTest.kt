package com.aurora.music.localization

import com.aurora.music.R
import java.lang.reflect.Modifier
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStringsTest {
    @After fun reset() = AppStrings.setLocale("")

    private fun ids(type: Class<*>) = type.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType && !it.name.startsWith("$") }
        .map { it.getInt(null) }

    private fun formats(text: String) =
        Regex("(?<!%)%(?:[0-9]+\\$)?[-+0-9.]*[sdf]").findAll(text).map { it.value }.sorted().toList()

    @Test fun everyIdResolvesInEveryLocale() {
        val strings = ids(R.string::class.java)
        val plurals = ids(R.plurals::class.java)
        assertTrue(strings.size > 2000)
        assertTrue(plurals.size > 10)
        for (tag in listOf("en", "ru")) {
            AppStrings.setLocale(tag)
            strings.forEach { appString(it) }
            plurals.forEach { id -> (0..112).forEach { appPlural(id, it) } }
        }
    }

    @Test fun russianFallsBackToEnglishAndKeepsFormatArguments() {
        AppStrings.setLocale("en")
        val english = ids(R.string::class.java).associateWith { appString(it) }
        AppStrings.setLocale("ru")
        assertEquals("Aurora", appString(R.string.app_name))
        assertEquals("Как в системе", appString(R.string.language_system))
        english.forEach { (id, text) -> assertEquals(text, formats(text), formats(appString(id))) }
    }

    @Test fun regionalTagFallsBackToLanguage() {
        AppStrings.setLocale("ru-RU")
        assertEquals("Как в системе", appString(R.string.language_system))
        AppStrings.setLocale("de")
        assertEquals("System default", appString(R.string.language_system))
    }

    @Test fun localeChangesArePublished() {
        AppStrings.setLocale("ru")
        assertEquals("ru", AppStrings.languageTag.value)
        assertEquals("ru", AppStrings.locale.language)
        assertEquals("ru", Locale.getDefault().language)
        AppStrings.setLocale("en")
        assertEquals("en", AppStrings.languageTag.value)
        assertEquals("System default", appString(R.string.language_system))
    }

    @Test fun formatsArgumentsWithTheActiveLocale() {
        AppStrings.setLocale("en")
        assertEquals("Bind \"EQ\" to USB", appString(R.string.text_bind_to_75e438, "EQ", "USB"))
        assertEquals("50% downloaded", appString(R.string.text_downloaded_a2ae75, "50"))
        assertEquals("%1\$s%% downloaded", appString(R.string.text_downloaded_a2ae75))
        assertTrue(appString(R.string.artwork_cache_usage, 1.5).startsWith("Artwork cache: 1.5 MB"))
        AppStrings.setLocale("ru")
        assertEquals("Привязать «EQ» к USB", appString(R.string.text_bind_to_75e438, "EQ", "USB"))
        assertTrue(appString(R.string.artwork_cache_usage, 1.5).startsWith("Кэш обложек: 1,5 МБ"))
    }

    @Test fun englishPlurals() {
        AppStrings.setLocale("en")
        assertEquals("1 track", appPlural(R.plurals.track_count, 1))
        listOf(0, 2, 5, 11, 21, 101).forEach { assertEquals("$it tracks", appPlural(R.plurals.track_count, it)) }
    }

    @Test fun russianPlurals() {
        AppStrings.setLocale("ru")
        listOf(1, 21, 101, 1001).forEach { assertEquals("$it трек", appPlural(R.plurals.track_count, it)) }
        listOf(2, 3, 4, 22, 34, 102).forEach { assertEquals("$it трека", appPlural(R.plurals.track_count, it)) }
        listOf(0, 5, 11, 12, 14, 19, 100, 111, 112).forEach { assertEquals("$it треков", appPlural(R.plurals.track_count, it)) }
    }

    @Test fun resolvesAndroidEscapes() {
        AppStrings.setLocale("en")
        assertEquals("A home for\nyour music.", appString(R.string.text_a_home_for_your_music_20a46f))
        assertEquals("A matching file in your device's music library", appString(R.string.text_a_matching_file_in_your_device_s_music_library_691446))
        assertEquals("  Live preview", appString(R.string.text_live_preview_d44c24))
        assertEquals("Set the Redirect URI to exactly:  aurora://spotify", appString(R.string.text_set_the_redirect_uri_to_exactly_aurora_spotify_407b7c))
        assertEquals("Access & Delivery", appString(R.string.text_access_delivery_270b49))
        assertEquals("Now playing controls", appString(R.string.widget_description))
    }

    @Test fun rejectsIdsOfTheWrongType() {
        assertThrows(IllegalArgumentException::class.java) { appString(R.plurals.track_count) }
        assertThrows(IllegalArgumentException::class.java) { appPlural(R.string.app_name, 1) }
    }
}
