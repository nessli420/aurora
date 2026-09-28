package com.aurora.music.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Same-directory atomic replacement keeps a failed backup write from damaging the previous file. */
fun persistBackupFileAtomically(file: File, bytes: ByteArray) {
    val temporary = File(file.parentFile, ".${file.name}-${UUID.randomUUID()}.tmp")
    try {
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        // Internal app storage supports atomic renames. A filesystem that does not support this
        // fails explicitly; silently falling back to a destructive replacement would break rollback.
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        temporary.delete()
    }
}
