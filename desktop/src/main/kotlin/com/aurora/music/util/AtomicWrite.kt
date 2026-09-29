package com.aurora.music.util

import java.io.File
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

inline fun File.writeAtomically(block: (OutputStream) -> Unit) {
    val target = absoluteFile
    val temporary = File.createTempFile(".aurora-", ".part", target.parentFile)
    try {
        temporary.outputStream().use { output ->
            block(output)
            output.fd.sync()
        }
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        temporary.delete()
    }
}
