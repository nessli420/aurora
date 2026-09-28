package com.aurora.music.desktop.platform

import com.aurora.music.util.AppLog
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class FileLog(private val dir: File, private val maxBytes: Long = 2L * 1024 * 1024, private val keep: Int = 3) {
    private val file = File(dir, "aurora.log")
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    fun write(level: AppLog.Level, tag: String, message: String, error: Throwable?) {
        val entry = "${LocalDateTime.now().format(stamp)} ${level.name.first()}/$tag: $message\n" + error?.stackTraceToString().orEmpty()
        System.err.print(entry)
        synchronized(this) {
            runCatching {
                dir.mkdirs()
                if (file.length() > 0 && file.length() + entry.length > maxBytes) rotate()
                file.appendText(entry)
            }
        }
    }

    private fun rotate() {
        for (index in keep - 1 downTo 1) {
            val older = File(dir, "aurora.$index.log")
            if (older.isFile) Files.move(older.toPath(), File(dir, "aurora.${index + 1}.log").toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        Files.move(file.toPath(), File(dir, "aurora.1.log").toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
