package com.aurora.music.localization

import com.aurora.music.R
import java.io.File
import java.lang.reflect.Modifier
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
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
        for (tag in listOf("en", "ru", "tr", "es", "fr", "zh", "pt", "hi", "ur", "de", "it", "nl", "pl", "id", "ja", "ko", "ar")) {
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
        AppStrings.setLocale("sv")
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

    @Test fun mergesDesktopStringsIntoTheSameTables() {
        AppStrings.setLocale("en")
        assertEquals("Exclusive mode", appString(R.string.text_exclusive_mode_01d9b2))
        assertEquals("88200 Hz is unavailable; using 44100 Hz.", appString(R.string.text_hz_is_unavailable_using_hz_14de1c, 88200, 44100))
        assertTrue(appString(R.string.text_aurora_takes_sole_control_of_the_device_and_sends_samples_at_the_cfbce6).contains("track's own rate"))
        assertEquals("Built with Compose Multiplatform & Material 3.", appString(R.string.text_built_with_compose_multiplatform_material_3_389c3a))
        AppStrings.setLocale("ru")
        assertEquals("Монопольный режим", appString(R.string.text_exclusive_mode_01d9b2))
        assertEquals("88200 Гц недоступно; используется 44100 Гц.", appString(R.string.text_hz_is_unavailable_using_hz_14de1c, 88200, 44100))
        assertEquals("Windows · Java 21", appString(R.string.text_windows_java_2566af, 21))
    }

    @Test fun everyDesktopStringHasARussianTranslation() {
        fun names(dir: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/$dir/strings_desktop.xml")).getElementsByTagName("string")
            .let { nodes -> (0 until nodes.length).map { nodes.item(it).attributes.getNamedItem("name").nodeValue } }
        val english = names("values")
        assertTrue(english.isNotEmpty())
        assertEquals(english.sorted(), names("values-ru").sorted())
    }

    @Test fun turkishStringsAndPlurals() {
        AppStrings.setLocale("tr")
        assertEquals("Sistem varsayılanı", appString(R.string.language_system))
        assertEquals("Özel mod", appString(R.string.text_exclusive_mode_01d9b2))
        assertEquals("%50 indirildi", appString(R.string.text_downloaded_a2ae75, "50"))
        listOf(0, 1, 2, 21).forEach { assertEquals("$it parça", appPlural(R.plurals.track_count, it)) }
        assertEquals("KİTAPLIK", appString(R.string.text_library_b8100f).uppercase(Locale.getDefault()))
    }

    @Test fun spanishFrenchAndChineseStringsAndPlurals() {
        AppStrings.setLocale("es")
        assertEquals("Predeterminado del sistema", appString(R.string.language_system))
        assertEquals("1 pista", appPlural(R.plurals.track_count, 1))
        assertEquals("2 pistas", appPlural(R.plurals.track_count, 2))
        AppStrings.setLocale("fr")
        assertEquals("Langue du système", appString(R.string.language_system))
        assertEquals("50 % téléchargé", appString(R.string.text_downloaded_a2ae75, "50"))
        assertEquals("2 pistes", appPlural(R.plurals.track_count, 2))
        AppStrings.setLocale("zh-CN")
        assertEquals("系统默认", appString(R.string.language_system))
        listOf(1, 2).forEach { assertEquals("$it 首曲目", appPlural(R.plurals.track_count, it)) }
    }

    @Test fun portugueseHindiAndUrduStringsAndPlurals() {
        AppStrings.setLocale("pt-BR")
        assertEquals("Padrão do sistema", appString(R.string.language_system))
        assertEquals("1 faixa", appPlural(R.plurals.track_count, 1))
        assertEquals("2 faixas", appPlural(R.plurals.track_count, 2))
        AppStrings.setLocale("hi")
        assertEquals("सिस्टम डिफ़ॉल्ट", appString(R.string.language_system))
        assertEquals("2 ट्रैक", appPlural(R.plurals.track_count, 2))
        AppStrings.setLocale("ur")
        assertTrue(appString(R.string.language_system).contains("سسٹم ڈیفالٹ"))
        assertTrue(appPlural(R.plurals.track_count, 2).contains("2 ٹریک"))
    }

    @Test fun europeanStringsAndPlurals() {
        AppStrings.setLocale("de")
        assertEquals("Systemstandard", appString(R.string.language_system))
        assertEquals("50% heruntergeladen", appString(R.string.text_downloaded_a2ae75, "50"))
        listOf(1, 2).forEach { assertEquals("$it Titel", appPlural(R.plurals.track_count, it)) }
        AppStrings.setLocale("it")
        assertEquals("1 brano", appPlural(R.plurals.track_count, 1))
        assertEquals("2 brani", appPlural(R.plurals.track_count, 2))
        AppStrings.setLocale("nl")
        assertEquals("1 nummer", appPlural(R.plurals.track_count, 1))
        assertEquals("2 nummers", appPlural(R.plurals.track_count, 2))
        AppStrings.setLocale("pl")
        assertEquals("1 utwór", appPlural(R.plurals.track_count, 1))
        listOf(2, 4, 22).forEach { assertEquals("$it utwory", appPlural(R.plurals.track_count, it)) }
        listOf(5, 12, 13, 25).forEach { assertEquals("$it utworów", appPlural(R.plurals.track_count, it)) }
    }

    @Test fun asianAndArabicStringsAndPlurals() {
        AppStrings.setLocale("id")
        assertEquals("Default sistem", appString(R.string.language_system))
        assertEquals("2 lagu", appPlural(R.plurals.track_count, 2))
        AppStrings.setLocale("ja")
        assertEquals("システムのデフォルト", appString(R.string.language_system))
        assertEquals("2 曲", appPlural(R.plurals.track_count, 2))
        AppStrings.setLocale("ko")
        assertEquals("시스템 기본값", appString(R.string.language_system))
        assertEquals("2곡", appPlural(R.plurals.track_count, 2))
        AppStrings.setLocale("ar")
        assertTrue(appString(R.string.language_system).contains("افتراضي النظام"))
        assertTrue(appPlural(R.plurals.track_count, 2).contains("المقاطع"))
    }

    @Test fun rejectsIdsOfTheWrongType() {
        assertThrows(IllegalArgumentException::class.java) { appString(R.plurals.track_count) }
        assertThrows(IllegalArgumentException::class.java) { appPlural(R.string.app_name, 1) }
    }
}
