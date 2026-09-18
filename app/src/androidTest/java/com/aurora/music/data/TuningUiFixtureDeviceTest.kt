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

// explicit device qa only
class TuningUiFixtureDeviceTest {
    @Test fun checkpointOrRestoreUserPreferencesForVisualQa() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val operation = InstrumentationRegistry.getArguments().getString("tuningUiFixture")
        assumeTrue(operation == "checkpoint" || operation == "restore")
        val context = instrumentation.targetContext
        val store = (context.applicationContext as AuroraApplication).container.settingsStore
        val file = File(context.filesDir, "r2b-ui-preferences-checkpoint.json")
        val assetCheckpoint = File(context.filesDir, "ui-impulse-files-checkpoint.json")
        val assetDirectory = File(context.filesDir, "impulse-library").canonicalFile
        if (operation == "checkpoint") {
            check(!file.exists()) { "An earlier UI checkpoint still needs restoration." }
            file.writeText(Gson().toJson(store.exportPrefs()))
            assetCheckpoint.writeText(Gson().toJson(assetDirectory.listFiles()?.map { it.name }.orEmpty()))
            assertTrue(file.length() > 0)
        } else {
            check(file.isFile) { "No UI checkpoint exists." }
            val original = Gson().fromJson(file.readText(), PrefsBackup::class.java)
            store.restoreBackupPrefs(original).getOrThrow()
            assertEquals(original, store.exportPrefs())
            assertEquals(TuningProjectCodec.decodeLibrary(original.strings[TuningProjectCodec.PREFERENCE_KEY]).getOrThrow(),
                TuningProjectCodec.decodeLibrary(store.exportPrefs().strings[TuningProjectCodec.PREFERENCE_KEY]).getOrThrow())
            if (assetCheckpoint.isFile) {
                val originalFiles = Gson().fromJson(assetCheckpoint.readText(), Array<String>::class.java).toSet()
                assetDirectory.listFiles().orEmpty().filter { it.name !in originalFiles }.forEach { asset ->
                    check(asset.canonicalFile.parentFile == assetDirectory && asset.isFile)
                    check(asset.name.matches(Regex("[0-9a-f-]{36}\\.wav")))
                    check(asset.delete())
                }
                check(assetCheckpoint.delete())
            }
            check(file.delete()) { "Restored checkpoint could not be removed." }
        }
    }
}
