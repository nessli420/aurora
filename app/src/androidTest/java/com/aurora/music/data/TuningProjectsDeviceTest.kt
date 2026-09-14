package com.aurora.music.data

import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.aurora.music.data.tuning.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class TuningProjectsDeviceTest {
    private val store get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AuroraApplication).container.settingsStore
    private suspend fun project(): TuningProject {
        val measurement = MeasurementTextImporter.parse("20 0\n100 0\n500 0\n1000 6\n2000 0\n10000 0\n20000 0", "Phone fixture").getOrThrow()
        val project = TuningProjectCodec.create("R2b phone fixture").copy(measurementLeft = measurement,
            measurementRight = measurement.copy(id = UUID.randomUUID().toString()), config = TuningFitConfig(bandBudget = 6))
        return project.copy(generatedFit = TuningFitter.fit(project))
    }

    @Test fun projectSaveRenamePortableRoundTripDeleteAndBackupKeepOriginalCurves() = runBlocking {
        val original = store.exportPrefs()
        try {
            val project = project()
            store.saveTuningProject(project).getOrThrow()
            assertEquals(project, store.tuningProjects.first().single { it.id == project.id })
            val renamed = project.copy(name = "R2b renamed")
            store.saveTuningProject(renamed).getOrThrow()
            assertEquals(renamed, store.tuningProjects.first().single { it.id == project.id })
            val portable = TuningProjectCodec.decodeProject(TuningProjectCodec.encodeProject(renamed)).getOrThrow()
            assertEquals(renamed, portable)
            val snapshot = store.exportPrefs()
            store.deleteTuningProject(project.id).getOrThrow()
            assertFalse(store.tuningProjects.first().any { it.id == project.id })
            store.restoreBackupPrefs(snapshot).getOrThrow()
            assertEquals(renamed, store.tuningProjects.first().single { it.id == project.id })
        } finally { store.restoreBackupPrefs(original).getOrThrow() }
    }

    @Test fun applyingFitKeepsModeExistingNodesAndFinalLimiterAndStoresProject() = runBlocking {
        val original = store.exportPrefs()
        try {
            val project = project()
            for (enabled in listOf(false, true)) {
                val limiter = ProcessingRackNode(UUID.randomUUID().toString(), "Final limiter", RackNodeKind.LIMITER)
                val graph = ProcessingRack(enabled = enabled, nodes = listOf(limiter))
                store.setProcessingRack(graph).getOrThrow()
                val mode = store.audioPrefs.first().dspMode
                store.appendTuningProjectToRack(project).getOrThrow()
                val updated = store.processingRack.first()
                assertEquals(enabled, updated.enabled)
                assertEquals(mode, store.audioPrefs.first().dspMode)
                assertEquals(limiter, updated.nodes.last())
                assertEquals(listOf(RackEqChannel.LEFT, RackEqChannel.RIGHT), updated.nodes.filter { it.kind == RackNodeKind.EQ }.map { it.eqChannel })
                assertEquals(project, store.tuningProjects.first().single { it.id == project.id })
            }
        } finally { store.restoreBackupPrefs(original).getOrThrow() }
    }

    @Test fun rejectedFitAndMalformedBackupMakeNoPartialPreferenceChanges() = runBlocking {
        val original = store.exportPrefs()
        try {
            val project = project()
            store.setProcessingRack(ProcessingRack(nodes = listOf(ProcessingRackNode(UUID.randomUUID().toString(), "Full EQ", RackNodeKind.EQ,
                audio = AudioPrefs(dspParametric = List(64) { ParamBand(1000f, 0f, 1f) }))))).getOrThrow()
            val before = store.exportPrefs()
            assertTrue(store.appendTuningProjectToRack(project).isFailure)
            assertEquals(before, store.exportPrefs())
            assertTrue(store.saveTuningProject(project.copy(config = project.config.copy(maxBoostDb = 2.0))).isFailure)
            assertEquals(before, store.exportPrefs())
            assertTrue(store.restoreBackupPrefs(before.copy(strings = before.strings + (TuningProjectCodec.PREFERENCE_KEY to "{broken"))).isFailure)
            assertEquals(before, store.exportPrefs())
        } finally { store.restoreBackupPrefs(original).getOrThrow() }
    }
}
