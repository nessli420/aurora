package com.aurora.music.desktop.platform

import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.emptyPreferences
import com.aurora.music.data.withoutCredentials
import com.aurora.music.desktop.natives.SystemNative
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
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
    private val protect: ((ByteArray) -> ByteArray?)? = if (HostPlatform.isWindows) { { SystemNative.protect(it, null) } } else null,
    private val unprotect: (ByteArray) -> ByteArray? = if (HostPlatform.isWindows) { { SystemNative.unprotect(it, null) } } else { { null } },
    private val scrub: (Preferences) -> Preferences = Preferences::withoutCredentials,
) : OkioSerializer<Preferences> {
    @Volatile
    private var unsealed: Pair<Preferences, Preferences>? = null

    override val defaultValue: Preferences get() = emptyPreferences()

    override suspend fun readFrom(source: BufferedSource): Preferences {
        val bytes = source.readByteArray()
        if (!isProtected(bytes)) {
            val stored = PreferencesSerializer.readFrom(Buffer().write(bytes))
            return unsealed?.takeIf { it.first == stored }?.second ?: stored
        }
        // another user or machine cannot decrypt the file so it starts over signed out
        val plain = runCatching { unprotect(bytes.copyOfRange(MAGIC.size, bytes.size)) }.getOrNull() ?: return emptyPreferences()
        return PreferencesSerializer.readFrom(Buffer().write(plain))
    }

    override suspend fun writeTo(t: Preferences, sink: BufferedSink) {
        val sealed = seal(serialize(t))
        if (sealed != null) {
            unsealed = null
            sink.write(sealed)
        } else {
            val scrubbed = scrub(t)
            unsealed = scrubbed to t
            sink.write(serialize(scrubbed))
        }
    }

    fun protectFile(file: File) {
        val bytes = file.takeIf(File::isFile)?.readBytes() ?: return
        if (bytes.isEmpty() || isProtected(bytes)) return
        val replacement = seal(bytes) ?: runBlocking {
            val stored = PreferencesSerializer.readFrom(Buffer().write(bytes))
            scrub(stored).takeIf { it != stored }?.let { serialize(it) }
        } ?: return
        val temporary = File(file.path + ".tmp")
        temporary.writeBytes(replacement)
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private suspend fun serialize(preferences: Preferences): ByteArray =
        Buffer().also { PreferencesSerializer.writeTo(preferences, it) }.readByteArray()

    private fun seal(plain: ByteArray): ByteArray? = protect?.let { runCatching { it(plain) }.getOrNull() }?.let { MAGIC + it }

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
