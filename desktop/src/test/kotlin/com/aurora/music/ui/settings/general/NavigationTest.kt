package com.aurora.music.ui.settings.general

import com.aurora.music.navigation.NavMenu
import com.aurora.music.navigation.NavPlacement
import com.aurora.music.navigation.Routes
import com.aurora.music.navigation.topLevelDestinations
import com.aurora.music.ui.screens.settings.SettingsDestinations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class NavigationTest {
    @Test fun routeArgumentsEncodeSpacesAsPercentTwenty() {
        assertEquals("folders?fid=C%3A%5CMusic%5CMy%20Album&title=Rock%20%26%20Roll", Routes.folders("C:\\Music\\My Album", "Rock & Roll"))
        assertEquals("smart_edit?id=a%2Bb%20c", Routes.smartEdit("a+b c"))
        val folder = Routes.folders("https://feeds.example.com/show?id=1&x=2", "Talk Show")
        assertFalse(folder.contains('+'))
        assertEquals("https://feeds.example.com/show?id=1&x=2", URLDecoder.decode(folder.substringAfter("fid=").substringBefore("&title"), Charsets.UTF_8))
        assertEquals(listOf(Routes.HOME, Routes.SEARCH, Routes.LIBRARY), topLevelDestinations.map { it.route })
        assertTrue(topLevelDestinations.all { it.label.isNotBlank() })
    }

    @Test fun defaultLayoutKeepsAndroidOnlyItemsOut() {
        val layout = NavMenu.parse("")
        assertEquals(listOf("home", "search", "library"), layout.main.map { it.id })
        assertEquals(listOf("history", "stats", "duplicates"), layout.more.map { it.id })
        assertEquals(listOf("playback", "output", "equalizer", "loudness", "advanced_audio", "signal_path"), layout.hidden.map { it.id })
        assertNull(NavMenu.byId("network"))
        assertNull(NavMenu.byId("radio"))
        assertNull(NavMenu.byId("podcasts"))
        assertTrue(NavMenu.items.all { it.label.isNotBlank() })
    }

    @Test fun savedLayoutsSurviveUnknownIdsAndKeepLockedItems() {
        val layout = NavMenu.parse("main=output,network,radio;more=home,podcasts,stats,network")
        assertEquals(setOf("home", "search", "library", "output"), layout.main.map { it.id }.toSet())
        assertEquals(listOf("stats"), layout.more.map { it.id })
        assertEquals(NavPlacement.HIDDEN, layout.placementOf("history"))

        val edited = layout.move("history", NavPlacement.MAIN).move("output", NavPlacement.MORE).shift("history", -1).move("home", NavPlacement.HIDDEN)
        assertEquals(NavPlacement.MAIN, edited.placementOf("home"))
        assertEquals(NavPlacement.MORE, edited.placementOf("output"))
        assertEquals(edited.main.map { it.id }, NavMenu.parse(edited.encode()).main.map { it.id })
        assertEquals(edited.more.map { it.id }, NavMenu.parse(edited.encode()).more.map { it.id })

        val advanced = NavMenu.parse("main=home,search,library,signal_path;more=loudness,stats")
        assertEquals(listOf("home", "search", "library"), advanced.visible(simpleMode = true).main.map { it.id })
        assertEquals(listOf("stats"), advanced.visible(simpleMode = true).more.map { it.id })
    }

    @Test fun settingsDestinationsAreUniqueAndDesktopOnly() {
        val all = SettingsDestinations.all
        assertEquals(all.size, all.map { it.route }.distinct().size)
        val androidOnly = setOf("settings_network", "settings_alarm", "settings_gestures", "settings_permissions", "settings_extensions")
        assertTrue(all.none { it.route in androidOnly })
        assertTrue(all.all { it.label.isNotBlank() && it.description.isNotBlank() })
    }
}
