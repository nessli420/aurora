package com.aurora.music.desktop.platform

import com.aurora.music.desktop.natives.SystemNative
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.concurrent.thread

class InstanceLockTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun secondLaunchSignalsTheRunningInstance() = runBlocking {
        val dir = temp.newFolder("local")
        val first = requireNotNull(InstanceLock.acquire(dir))
        first.use {
            val activated = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5000) { first.activations.first() } }
            val granted = mutableListOf<Long>()
            assertNull(InstanceLock.acquire(dir) { granted += it })
            activated.await()
            assertEquals(listOf(ProcessHandle.current().pid()), granted)
        }
        assertFalse(File(dir, "instance.port").exists())
        assertFalse(InstanceLock.signal(dir))
        requireNotNull(InstanceLock.acquire(dir)).close()
    }

    @Test fun aLaunchWhileTheOldInstanceQuitsWaitsForItsLock() {
        val dir = temp.newFolder("local")
        val old = requireNotNull(InstanceLock.acquire(dir))
        old.stopListening()
        thread { Thread.sleep(300); old.close() }
        val started = System.nanoTime()
        requireNotNull(InstanceLock.acquire(dir, waitMs = 5_000) {}).close()
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 250)
    }

    @Test fun theForegroundGrantLinksAgainstTheNativeLibrary() {
        assumeTrue(HostPlatform.isWindows)
        SystemNative.allowForeground(ProcessHandle.current().pid())
    }
}
