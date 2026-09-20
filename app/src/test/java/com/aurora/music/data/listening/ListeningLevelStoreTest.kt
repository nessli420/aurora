package com.aurora.music.data.listening

import com.aurora.music.data.routes.ProcessingRoute
import com.aurora.music.data.routes.ProcessingRouteMonitor
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ListeningLevelStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun savedCalibrationRequiresCurrentHardwareConfirmation() = runBlocking {
        val (store, routes) = fixture()
        store.saveProfile(ListeningLevelsTest.profile(), routes.current).getOrThrow()
        store.observe(observation(routes))
        assertTrue(store.estimate.value is ListeningEstimate.Unavailable)
        store.confirm("calibration-1", routes.current).getOrThrow()
        assertTrue(store.estimate.value is ListeningEstimate.Available)
        store.invalidateConfirmation()
        assertTrue(store.estimate.value is ListeningEstimate.Unavailable)
    }

    @Test fun routeReconnectCannotReuseConfirmationOrStaleBindDialog() = runBlocking {
        val (store, routes) = fixture()
        val before = routes.current
        store.saveProfile(ListeningLevelsTest.profile(), before).getOrThrow()
        store.confirm("calibration-1", before).getOrThrow()
        routes.publish(ProcessingRoute())
        routes.publish(ListeningLevelsTest.route())
        store.observe(observation(routes))
        assertTrue(store.estimate.value is ListeningEstimate.Unavailable)
        assertTrue(store.confirm("calibration-1", before).isFailure)
        assertTrue(store.saveProfile(ListeningLevelsTest.profile(), before).isFailure)
    }

    @Test fun historyIsOptInThrottledAndSkipsPrivateSessions() = runBlocking {
        val (store, routes) = fixture()
        store.saveProfile(ListeningLevelsTest.profile(), routes.current).getOrThrow()
        store.confirm("calibration-1", routes.current).getOrThrow()
        store.observe(observation(routes))
        assertTrue(store.state.value.history.isEmpty())
        store.setHistoryEnabled(true).getOrThrow()
        store.observe(observation(routes).copy(allowHistory = false))
        assertTrue(store.state.value.history.isEmpty())
        store.observe(observation(routes))
        store.observe(observation(routes))
        assertEquals(1, store.state.value.history.size)
        store.observe(observation(routes, seconds = 30))
        assertEquals(2, store.state.value.history.size)
        store.setHistoryEnabled(false).getOrThrow()
        store.observe(observation(routes, seconds = 60))
        assertEquals(2, store.state.value.history.size)
        store.clearHistory().getOrThrow()
        assertTrue(store.state.value.history.isEmpty())
    }

    @Test fun historyIsBoundedAndRestoreNeverRestoresConfirmation() = runBlocking {
        val (store, routes) = fixture()
        store.saveProfile(ListeningLevelsTest.profile(), routes.current).getOrThrow()
        store.confirm("calibration-1", routes.current).getOrThrow()
        store.setHistoryEnabled(true).getOrThrow()
        repeat(ListeningLevelCodec.MAX_HISTORY + 3) { index -> store.observe(observation(routes, seconds = index.toLong() * 30)) }
        assertEquals(ListeningLevelCodec.MAX_HISTORY, store.state.value.history.size)
        store.setHistoryEnabled(false).getOrThrow()
        val restored = ListeningLevelStore(File(temporary.root, "levels.json"), routes)
        restored.observe(observation(routes))
        assertEquals(1, restored.state.value.profiles.size)
        assertTrue(restored.estimate.value is ListeningEstimate.Unavailable)
    }

    @Test fun importingPortableProfilesRequiresFreshBinding() = runBlocking {
        val (store, routes) = fixture()
        store.saveProfile(ListeningLevelsTest.profile(), routes.current).getOrThrow()
        store.confirm("calibration-1", routes.current).getOrThrow()
        store.importProfiles(store.exportProfiles()).getOrThrow()
        assertNull(store.state.value.profiles.single().routeKey)
        assertTrue(store.confirm("calibration-1", routes.current).isFailure)
        store.observe(observation(routes))
        assertTrue(store.estimate.value is ListeningEstimate.Unavailable)
    }

    @Test fun malformedStoredDataStaysUnavailable() {
        val file = File(temporary.root, "levels.json").also { it.writeText("{\"version\":1,\"profiles\":[{}]}") }
        val store = ListeningLevelStore(file, ProcessingRouteMonitor())
        assertNotNull(store.state.value.error)
        assertTrue(store.state.value.profiles.isEmpty())
        assertTrue(store.estimate.value is ListeningEstimate.Unavailable)
    }

    @Test fun backupReplacementKeepsLocalHistoryAndRollsBackProfiles() = runBlocking {
        val (store, routes) = fixture()
        store.saveProfile(ListeningLevelsTest.profile(), routes.current).getOrThrow()
        store.confirm("calibration-1", routes.current).getOrThrow()
        store.setHistoryEnabled(true).getOrThrow()
        store.observe(observation(routes))
        val previous = store.state.value
        val token = store.replaceBackupProfiles(ListeningLevelCodec.encode(ListeningState()))
        assertTrue(store.state.value.profiles.isEmpty())
        assertEquals(previous.history, store.state.value.history)
        assertTrue(store.state.value.historyEnabled)
        assertTrue(store.rollbackBackup(token))
        assertEquals(previous, store.state.value)
        assertTrue(store.estimate.value is ListeningEstimate.Unavailable)
    }

    @Test fun backupRollbackCannotOverwriteLaterEdits() = runBlocking {
        val (store, routes) = fixture()
        store.saveProfile(ListeningLevelsTest.profile(), routes.current).getOrThrow()
        val token = store.replaceBackupProfiles(store.exportProfiles())
        store.clearHistory().getOrThrow()
        assertFalse(store.rollbackBackup(token))
        assertNull(store.state.value.profiles.single().routeKey)
    }

    private fun fixture(): Pair<ListeningLevelStore, ProcessingRouteMonitor> {
        val routes = ProcessingRouteMonitor().also { it.publish(ListeningLevelsTest.route()) }
        return ListeningLevelStore(File(temporary.root, "levels.json"), routes).also { it.observe(observation(routes)) } to routes
    }

    private fun observation(routes: ProcessingRouteMonitor, seconds: Long = 0): ListeningObservation {
        val initial = ListeningLevelsTest.observation()
        val now = initial.nowNanos + seconds * 1_000_000_000L
        return initial.copy(route = routes.current, nowNanos = now, timestampMillis = initial.timestampMillis + seconds * 1000,
            postDsp = initial.postDsp!!.copy(measuredAtNanos = now))
    }
}
