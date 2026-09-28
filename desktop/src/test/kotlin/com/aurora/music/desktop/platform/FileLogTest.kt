package com.aurora.music.desktop.platform

import com.aurora.music.util.AppLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileLogTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun rotatesAndKeepsStackTraces() {
        val dir = temp.newFolder("logs")
        val log = FileLog(dir, maxBytes = 300, keep = 2)
        repeat(30) { log.write(AppLog.Level.DEBUG, "Scan", "message number $it", null) }
        log.write(AppLog.Level.ERROR, "Scan", "last", IllegalStateException("broken"))
        assertEquals(setOf("aurora.log", "aurora.1.log", "aurora.2.log"), dir.list()!!.toSet())
        val current = File(dir, "aurora.log").readText()
        assertTrue(current, current.contains(" E/Scan: last\n"))
        assertTrue(current, current.contains("java.lang.IllegalStateException: broken"))
        assertTrue(File(dir, "aurora.1.log").readText().contains("D/Scan: message number 29\n"))
    }
}
