package com.aurora.music.ui.shell

import androidx.savedstate.read
import com.aurora.music.desktop.platform.DesktopSettings
import com.aurora.music.desktop.ui.Shortcut
import com.aurora.music.model.LibraryFilter
import com.aurora.music.navigation.Routes
import com.aurora.music.ui.layout.PageMetrics
import com.aurora.music.ui.layout.TabletMetrics
import com.aurora.music.ui.testing.edt
import com.aurora.music.ui.uxaudit.AuditScene
import com.aurora.music.viewmodel.LibraryViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SplitPanesTest {
    private val start = TabletMetrics.SidebarWidth.value + TabletMetrics.NavGap.value
    private val half = TabletMetrics.DividerWidth.value / 2

    private fun AuditScene.saved(read: suspend DesktopSettings.() -> Float?, done: (Float?) -> Boolean): Float? {
        val until = System.currentTimeMillis() + 8_000
        while (true) {
            val value = runBlocking { container.desktopSettings.read() }
            if (done(value)) return value
            if (System.currentTimeMillis() > until) fail("saved width stayed at $value")
            settle(50)
        }
    }

    private fun AuditScene.width(read: suspend DesktopSettings.() -> Float?, near: Float): Float =
        saved(read) { it != null && kotlin.math.abs(it - near) <= 4f }!!

    private fun AuditScene.openAlbumSplit() {
        awaitRoute(Routes.HOME)
        navigate(Routes.LIBRARY)
        entryViewModel(LibraryViewModel::class).setFilter(LibraryFilter.ALBUMS)
        settle()
        click(contentLeft + 60f, PageMetrics.HeaderTop.value + PageMetrics.HeaderHeight.value + 112f)
        settle(2_000)
    }

    private fun AuditScene.previousRoute(): String? = edt { nav.previousBackStackEntry?.destination?.route }

    @Test fun libraryDividerResizesClampsPersistsAndResets() {
        AuditScene("split-library-resize", 1440, 900).use { scene ->
            scene.openAlbumSplit()
            val y = 420f
            val list: suspend DesktopSettings.() -> Float? = { libraryListWidth.first() }
            val max = 1440f - start - TabletMetrics.SplitPaneMinWidth.value - TabletMetrics.DividerWidth.value - TabletMetrics.WindowInset.value

            scene.drag(start + 440f + half, y, 100f)
            val dragged = scene.width(list, 540f)
            scene.drag(start + dragged + half, y, 1_000f)
            assertEquals(max, scene.saved(list) { it == max })
            scene.drag(start + max + half, y, -1_000f)
            assertEquals(TabletMetrics.SplitListMinWidth.value, scene.saved(list) { it == TabletMetrics.SplitListMinWidth.value })

            scene.doubleClick(start + TabletMetrics.SplitListMinWidth.value + half, y)
            assertNull(scene.saved(list) { it == null })
            scene.drag(start + 440f + half, y, -40f)
            scene.width(list, 400f)

            runBlocking { scene.container.desktopSettings.setLibraryListWidth(2_000f) }
            scene.settle()
            scene.drag(start + max + half, y, -100f)
            scene.width(list, max - 100f)
            scene.shot("split-library-resized")
        }
    }

    @Test fun settingsDividerResizesClampsPersistsAndResets() {
        AuditScene("split-settings-resize", 1440, 900).use { scene ->
            scene.awaitRoute(Routes.HOME)
            scene.navigate(Routes.SETTINGS)
            val y = 420f
            val list: suspend DesktopSettings.() -> Float? = { settingsListWidth.first() }

            scene.drag(start + 320f + half, y, 60f)
            val dragged = scene.width(list, 380f)
            scene.drag(start + dragged + half, y, 1_000f)
            assertEquals(TabletMetrics.SettingsListMaxWidth.value, scene.saved(list) { it == TabletMetrics.SettingsListMaxWidth.value })
            scene.drag(start + TabletMetrics.SettingsListMaxWidth.value + half, y, -1_000f)
            assertEquals(TabletMetrics.SettingsListMinWidth.value, scene.saved(list) { it == TabletMetrics.SettingsListMinWidth.value })
            scene.doubleClick(start + TabletMetrics.SettingsListMinWidth.value + half, y)
            assertNull(scene.saved(list) { it == null })
            assertEquals(Routes.SETTINGS, scene.route)
        }
    }

    @Test fun nowPlayingPanelResizesFromItsLeftEdge() {
        AuditScene("split-panel-resize", 1440, 900).use { scene ->
            scene.awaitRoute(Routes.HOME)
            scene.press(Shortcut.QUEUE)
            val y = 420f
            val panel: suspend DesktopSettings.() -> Float? = { sidePanelWidth.first() }
            val edge = { width: Float -> 1440f - width - half }

            scene.drag(edge(TabletMetrics.SidePanelWidth.value), y, -100f)
            val dragged = scene.width(panel, 460f)
            scene.drag(edge(dragged), y, -1_000f)
            assertEquals(TabletMetrics.SidePanelMaxWidth.value, scene.saved(panel) { it == TabletMetrics.SidePanelMaxWidth.value })
            scene.shot("split-panel-wide")
            scene.drag(edge(TabletMetrics.SidePanelMaxWidth.value), y, 1_000f)
            assertEquals(TabletMetrics.SidePanelMinWidth.value, scene.saved(panel) { it == TabletMetrics.SidePanelMinWidth.value })
            scene.doubleClick(edge(TabletMetrics.SidePanelMinWidth.value), y)
            assertNull(scene.saved(panel) { it == null })
        }
    }

    @Test fun nowPlayingPanelStaysWithinASmallerWindow() {
        AuditScene("split-panel-small", 1100, 800, setup = { desktopSettings.setSidePanelWidth(TabletMetrics.SidePanelMaxWidth.value) }).use { scene ->
            scene.awaitRoute(Routes.HOME)
            scene.press(Shortcut.QUEUE)
            val room = 1100f - TabletMetrics.RailWidth.value - TabletMetrics.MinContentWidth.value
            scene.drag(1100f - room - half, 380f, 50f)
            scene.width({ sidePanelWidth.first() }, room - 50f)
        }
    }

    @Test fun librarySplitOpensAsAFullPageAndBackReturnsToTheLibrary() {
        AuditScene("split-library-expand", 1440, 900).use { scene ->
            scene.openAlbumSplit()
            scene.shot("split-library-before-expand")
            val headerEnd = 1440f - TabletMetrics.WindowInset.value - PageMetrics.PaneGutter.value
            scene.click(headerEnd - 48f - 48f / 2 + 12f, 40f)
            scene.awaitRoute(Routes.DETAIL)
            val (kind, id) = edt { scene.nav.currentBackStackEntry!!.arguments!!.read { getStringOrNull("kind") to getStringOrNull("id") } }
            assertEquals("album", kind)
            assertTrue(scene.container.folderLibrary.albums.any { it.id == id })
            assertEquals(Routes.LIBRARY, scene.previousRoute())
            scene.shot("split-library-expanded")

            val gutter = scene.gutter
            scene.click(1440f - gutter - 48f - 48f / 2 + 12f, 40f)
            assertEquals(Routes.DETAIL, scene.route)
            assertEquals(Routes.LIBRARY, scene.previousRoute())

            scene.press(Shortcut.BACK)
            scene.awaitRoute(Routes.LIBRARY)
            scene.press(Shortcut.BACK)
            scene.awaitRoute(Routes.HOME)
        }
    }

    @Test fun settingsPaneOpensAsAFullPageAndBackReturnsToSettings() {
        AuditScene("split-settings-expand", 1440, 900).use { scene ->
            scene.awaitRoute(Routes.HOME)
            scene.navigate(Routes.SETTINGS)
            val headerEnd = 1440f - TabletMetrics.WindowInset.value - PageMetrics.PaneGutter.value
            val headerY = PageMetrics.HeaderTop.value + PageMetrics.HeaderHeight.value / 2
            scene.click(headerEnd - 48f / 2 + 14f, headerY)
            scene.awaitRoute(Routes.SETTINGS_PLAYBACK)
            assertEquals(Routes.SETTINGS, scene.previousRoute())
            scene.shot("split-settings-expanded")

            scene.click(1440f - scene.gutter - 48f / 2 + 14f, headerY)
            assertEquals(Routes.SETTINGS_PLAYBACK, scene.route)
            assertEquals(Routes.SETTINGS, scene.previousRoute())

            scene.press(Shortcut.BACK)
            scene.awaitRoute(Routes.SETTINGS)
        }
    }
}
