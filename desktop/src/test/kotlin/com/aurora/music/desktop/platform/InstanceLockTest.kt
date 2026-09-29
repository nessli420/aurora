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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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

    @Test fun theForegroundGrantLinksAgainstTheNativeLibrary() {
        SystemNative.allowForeground(ProcessHandle.current().pid())
    }
}
