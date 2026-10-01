package com.aurora.music.desktop.platform

import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.emptyPreferences
import com.aurora.music.desktop.natives.SystemNative
import kotlinx.coroutines.CoroutineScope
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ProtectedPreferencesSerializer(
    private val protect: (ByteArray) -> ByteArray? = { SystemNative.protect(it, null) },
    private val unprotect: (ByteArray) -> ByteArray? = { SystemNative.unprotect(it, null) },
) : OkioSerializer<Preferences> {
    override val defaultValue: Preferences get() = emptyPreferences()

    override suspend fun readFrom(source: BufferedSource): Preferences {
        val bytes = source.readByteArray()
        if (!isProtected(bytes)) return PreferencesSerializer.readFrom(Buffer().write(bytes))
        // another user or machine cannot decrypt the file so it starts over signed out
        val plain = unprotect(bytes.copyOfRange(MAGIC.size, bytes.size)) ?: return emptyPreferences()
        return PreferencesSerializer.readFrom(Buffer().write(plain))
    }

    override suspend fun writeTo(t: Preferences, sink: BufferedSink) {
        sink.write(seal(Buffer().also { PreferencesSerializer.writeTo(t, it) }.readByteArray()))
    }

    fun protectFile(file: File) {
        val bytes = file.takeIf(File::isFile)?.readBytes() ?: return
        if (bytes.isEmpty() || isProtected(bytes)) return
        val sealed = seal(bytes).takeIf(::isProtected) ?: return
        val temporary = File(file.path + ".tmp")
        temporary.writeBytes(sealed)
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun seal(plain: ByteArray): ByteArray = runCatching { protect(plain) }.getOrNull()?.let { MAGIC + it } ?: plain

    private fun isProtected(bytes: ByteArray): Boolean = bytes.size >= MAGIC.size && MAGIC.indices.all { bytes[it] == MAGIC[it] }

    private companion object {
        val MAGIC = "aurora-dpapi\n".toByteArray()
    }
}

fun protectedPreferencesStore(
    file: File,
    scope: CoroutineScope,
    serializer: ProtectedPreferencesSerializer = ProtectedPreferencesSerializer(),
): DataStore<Preferences> {
    runCatching { serializer.protectFile(file) }
    return PreferenceDataStoreFactory.create(OkioStorage(RetryingFileSystem(), serializer) { file.absoluteFile.toOkioPath() }, scope = scope)
}

fun preferencesStore(file: File, scope: CoroutineScope): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(OkioStorage(RetryingFileSystem(), PreferencesSerializer) { file.absoluteFile.toOkioPath() }, scope = scope)

// defender and the search indexer briefly lock freshly written files, which fails the datastore rename
internal class RetryingFileSystem(delegate: FileSystem = SYSTEM) : ForwardingFileSystem(delegate) {
    override fun atomicMove(source: Path, target: Path) {
        for (attempt in 1..ATTEMPTS) {
            try {
                return super.atomicMove(source, target)
            } catch (e: AccessDeniedException) {
                if (attempt == ATTEMPTS) throw e
                Thread.sleep(attempt * 25L)
            }
        }
    }

    private companion object {
        const val ATTEMPTS = 8
    }
}
