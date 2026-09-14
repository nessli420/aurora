package com.aurora.music.data

import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.aurora.music.data.tuning.TuningProjectCodec
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Explicit phone QA checkpoint; never runs as part of an ordinary test invocation. */
class TuningUiFixtureDeviceTest {
    @Test fun checkpointOrRestoreUserPreferencesForVisualQa() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val operation = InstrumentationRegistry.getArguments().getString("tuningUiFixture")
        assumeTrue(operation == "checkpoint" || operation == "restore")
        val context = instrumentation.targetContext
        val store = (context.applicationContext as AuroraApplication).container.settingsStore
        val file = File(context.filesDir, "r2b-ui-preferences-checkpoint.json")
        if (operation == "checkpoint") {
            check(!file.exists()) { "An earlier UI checkpoint still needs restoration." }
            file.writeText(Gson().toJson(store.exportPrefs()))
            assertTrue(file.length() > 0)
        } else {
            check(file.isFile) { "No UI checkpoint exists." }
            val original = Gson().fromJson(file.readText(), PrefsBackup::class.java)
            store.restoreBackupPrefs(original).getOrThrow()
            assertEquals(original, store.exportPrefs())
            assertEquals(TuningProjectCodec.decodeLibrary(original.strings[TuningProjectCodec.PREFERENCE_KEY]).getOrThrow(),
                TuningProjectCodec.decodeLibrary(store.exportPrefs().strings[TuningProjectCodec.PREFERENCE_KEY]).getOrThrow())
            check(file.delete()) { "Restored checkpoint could not be removed." }
        }
    }
}
