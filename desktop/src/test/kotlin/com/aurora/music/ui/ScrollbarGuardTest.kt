package com.aurora.music.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ScrollbarGuardTest {
    @Test fun desktopSourcesDrawNoScrollbars() {
        val sources = File("src/main/kotlin")
        assertTrue(sources.isDirectory)
        val banned = Regex("\\b(VerticalScrollbar|HorizontalScrollbar|rememberScrollbarAdapter|ScrollbarStyle|LocalScrollbarStyle|ScrollbarAdapter)\\b")
        val offenders = sources.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            file.readLines().withIndex().filter { banned.containsMatchIn(it.value) }.map { "${file.path}:${it.index + 1}" }
        }.toList()
        assertEquals(emptyList<String>(), offenders)
    }
}
