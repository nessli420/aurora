package com.aurora.music.ui.settings.general

import androidx.compose.foundation.layout.PaddingValues
import com.aurora.music.data.ExportedPlay
import com.aurora.music.data.HistoryImports
import com.aurora.music.data.HistoryService
import com.aurora.music.ui.screens.settings.HistoryImportScreen
import com.aurora.music.ui.screens.settings.StorageSettingsScreen
import com.aurora.music.ui.testing.distinctColors
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class HistoryImportScreenTest {
    private val padding = PaddingValues()
    private fun at(text: String) = Instant.parse(text).toEpochMilli()

    private val rows = listOf(
        ExportedPlay(HistoryService.SPOTIFY, at("2024-03-01T21:07:42Z"), true, "Glass Harbor", "Nova Pilot", "Harbor Lights", 201_344),
        ExportedPlay(HistoryService.YOUTUBE_MUSIC, at("2024-05-01T08:15:02Z"), false, "Paper Tides", "Lumen Field", "", null),
    )

    @Test fun thePageRendersEmptyAndWithPastImportsAndUndoEmptiesIt() {
        GeneralSettingsScene("history-import-empty", height = 1800) { HistoryImportScreen(padding, onBack = {}, confirm = {}) }
            .use { assertTrue(it.shot().distinctColors() > 20) }
        GeneralSettingsScene("history-import-records", height = 1800, seed = {
            val preview = HistoryImports.plan(rows, emptyMap(), emptyList(), playHistory.snapshot(), emptyList(), at("2026-11-20T12:00:00Z"))
            HistoryImports.commit(preview, playHistory, null)
        }) { HistoryImportScreen(padding, onBack = {}, confirm = {}) }.use { scene ->
            assertTrue(scene.shot().distinctColors() > 20)
            val records = scene.await(read = { historyImporter.records.first() }) { it.size == 2 }
            records.forEach { record -> scene.await(read = { historyImporter.undo(record.origin) }) { true } }
            assertEquals(0, scene.await(read = { historyImporter.records.first() }) { it.isEmpty() }.size)
            assertTrue(scene.container.playHistory.snapshot().isEmpty())
            scene.shot("-undone")
        }
    }

    @Test fun theStorageHubListsImportedHistory() {
        GeneralSettingsScene("history-import-storage", height = 2600, seed = {
            HistoryImports.commit(HistoryImports.plan(rows, emptyMap(), emptyList(), emptyList(), emptyList(), at("2026-11-20T12:00:00Z")), playHistory, null)
        }) { StorageSettingsScreen(padding, onBack = {}) }.use { assertTrue(it.shot().distinctColors() > 20) }
    }
}
