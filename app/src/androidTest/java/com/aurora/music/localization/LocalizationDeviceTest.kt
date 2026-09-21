package com.aurora.music.localization

import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.R
import com.aurora.music.model.LibraryFilter
import com.aurora.music.model.releaseTypeLabel
import com.aurora.music.navigation.topLevelDestinations
import com.aurora.music.ui.screens.settings.SettingsDestinations
import com.aurora.music.ui.theme.AccentPresets
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class LocalizationDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun configuration(language: String) = Configuration(context.resources.configuration).apply {
        setLocales(LocaleList(Locale.forLanguageTag(language)))
    }

    @Test fun russianUsesTheCorrectCountForms() {
        val resources = context.createConfigurationContext(configuration("ru")).resources
        val expected = mapOf(0 to "0 треков", 1 to "1 трек", 2 to "2 трека", 5 to "5 треков",
            11 to "11 треков", 21 to "21 трек", 22 to "22 трека", 25 to "25 треков", 101 to "101 трек")
        expected.forEach { (count, text) ->
            assertEquals(text, resources.getQuantityString(R.plurals.track_count, count, count))
        }
        val english = context.createConfigurationContext(configuration("en")).resources
        assertEquals("1 track", english.getQuantityString(R.plurals.track_count, 1, 1))
        assertEquals("2 tracks", english.getQuantityString(R.plurals.track_count, 2, 2))
    }

    @Test fun existingLabelsRefreshWithoutChangingMediaIdentifiers() {
        val original = Configuration(AppStrings.context.resources.configuration)
        try {
            AppStrings.useConfiguration(configuration("en"))
            val navigation = topLevelDestinations
            val destination = SettingsDestinations.about
            val filter = LibraryFilter.ALBUMS
            val accent = AccentPresets.first().name
            assertEquals("Home", navigation.first().label)
            assertEquals("Albums", filter.label)
            AppStrings.useConfiguration(configuration("ru"))
            assertEquals("Главная", navigation.first().label)
            assertEquals("Альбомы", filter.label)
            assertEquals("О приложении Aurora", destination.label)
            assertNotEquals(accent, AccentPresets.first().name)
            assertEquals("Single", releaseTypeLabel("single"))
            assertEquals("Сингл", releaseTypeLabel("single").localizedMediaType())
        } finally {
            AppStrings.useConfiguration(original)
        }
    }

    @Test fun translationsRetainFormattingArguments() {
        val english = context.createConfigurationContext(configuration("en")).resources
        val russian = context.createConfigurationContext(configuration("ru")).resources
        val format = Regex("(?<!%)%(?:[0-9]+\\$)?[-+0-9.]*[sdf]")
        val fields = R.string::class.java.fields.filter { it.name.startsWith("text_") }
        assertTrue(fields.size > 2000)
        fields.forEach { field ->
            val id = field.getInt(null)
            assertEquals(field.name, format.findAll(english.getString(id)).map { it.value }.sorted().toList(),
                format.findAll(russian.getString(id)).map { it.value }.sorted().toList())
        }
    }
}
