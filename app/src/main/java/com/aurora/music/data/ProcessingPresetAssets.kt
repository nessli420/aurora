package com.aurora.music.data

import com.aurora.music.data.ir.ImpulseLibraryEntry
import com.aurora.music.data.ir.ImpulseLibraryFiles

object ProcessingPresetAssets {
    fun remapEntries(entries: List<ImpulseLibraryEntry>, asset: (String, String) -> Pair<String, String>): List<ImpulseLibraryEntry> = entries.map { entry ->
        val (path, hash) = asset(entry.sourcePath, entry.sourceSha256)
        entry.copy(sourcePath = path, sourceSha256 = hash, prepared = entry.prepared?.let { prepared ->
            val (preparedPath, preparedHash) = asset(prepared.path, prepared.sha256)
            prepared.copy(path = preparedPath, sha256 = preparedHash)
        })
    }

    fun validateFiles(entries: List<ImpulseLibraryEntry>) {
        entries.forEach { entry ->
            ImpulseLibraryFiles.validateAsset(entry, false).getOrThrow()
            if (entry.prepared != null) ImpulseLibraryFiles.validateAsset(entry, true).getOrThrow()
        }
    }
}
