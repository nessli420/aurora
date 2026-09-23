package com.aurora.music.data

import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.aurora.music.playback.engine.OutputRateMode
import com.aurora.music.playback.engine.OutputRatePolicy
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputRatePolicyDeviceTest {
    private val store get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AuroraApplication).container.settingsStore

    @Test fun independentRapidEditsKeepEveryOutputSetting() = runBlocking {
        val original = store.exportPrefs()
        try {
            store.setOutputRatePolicy(OutputRatePolicy()).getOrThrow()
            coroutineScope {
                val rate = async { store.updateOutputRatePolicy { it.copy(mode = OutputRateMode.FIXED, fixedRate = 96_000) } }
                val dither = async { store.updateOutputRatePolicy { it.copy(tpdfDither = true, noiseShaping = true) } }
                rate.await().getOrThrow()
                dither.await().getOrThrow()
            }
            val policy = store.outputRatePolicy.first()
            assertEquals(OutputRateMode.FIXED, policy.mode)
            assertEquals(96_000, policy.fixedRate)
            assertTrue(policy.tpdfDither)
            assertTrue(policy.noiseShaping == true)
        } finally {
            store.restoreBackupPrefs(original).getOrThrow()
        }
    }
}
