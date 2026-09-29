package com.aurora.music.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class AtomicWriteTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun replacesTheTargetOnSuccess() {
        val target = temp.newFile("aurora-backup.zip").apply { writeText("old backup") }
        target.writeAtomically { it.write("new backup".toByteArray()) }
        assertEquals("new backup", target.readText())
        assertEquals(listOf("aurora-backup.zip"), temp.root.list()!!.toList())
    }

    @Test fun keepsTheExistingFileWhenTheWriteFails() {
        val target = temp.newFile("aurora-backup.zip").apply { writeText("old backup") }
        val failure = runCatching {
            target.writeAtomically {
                it.write("partial".toByteArray())
                throw IOException("An impulse response is missing")
            }
        }
        assertTrue(failure.exceptionOrNull() is IOException)
        assertEquals("old backup", target.readText())
        assertEquals(listOf("aurora-backup.zip"), temp.root.list()!!.toList())
    }

    @Test fun createsANewFile() {
        val target = temp.root.resolve("preset.zip")
        target.writeAtomically { it.write(byteArrayOf(1, 2, 3)) }
        assertEquals(listOf<Byte>(1, 2, 3), target.readBytes().toList())
    }
}
