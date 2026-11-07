package com.aurora.music.data

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface LyricsTranslation {
    data class Ready(val lines: List<String?>) : LyricsTranslation
    data class Pending(val language: String, val needsWifi: Boolean) : LyricsTranslation
}

// everything runs on the phone; only the language packs are fetched once, on wi-fi
class LyricsTranslator(private val context: Context, private val canDownload: () -> Boolean) {
    // the manifest drops ml kit's init provider; every ml kit call goes through these two first
    private val identifier by lazy { MlKitContext.initializeIfNeeded(context); LanguageIdentification.getClient() }
    private val models by lazy { MlKitContext.initializeIfNeeded(context); RemoteModelManager.getInstance() }
    private val recent = object : LinkedHashMap<String, List<String?>>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String?>>) = size > 24
    }

    // one entry per line, null where a line is blank or already in the target language
    fun translate(lines: List<String>, target: String): Flow<LyricsTranslation> = flow {
        val to = supported(target) ?: return@flow
        val key = "$to|${lines.hashCode()}"
        synchronized(recent) { recent[key] }?.let { emit(LyricsTranslation.Ready(it)); return@flow }
        val distinct = lines.map(String::trim).filter { it.any(Char::isLetter) }.distinct()
        if (distinct.isEmpty()) return@flow
        val song = supported(identifier.identifyLanguage(distinct.joinToString("\n").take(SAMPLE)).await()) ?: return@flow
        // a short line can be misread as a neighbouring language, so only english or the target overrides the song
        val sources = distinct.associateWith { line ->
            supported(identifier.identifyLanguage(line).await()).takeIf { it == to || it == TranslateLanguage.ENGLISH } ?: song
        }.filterValues { it != to }
        if (sources.isEmpty()) {
            // already in the target language: remember that so reopening skips language id
            synchronized(recent) { recent[key] = List(lines.size) { null } }
            return@flow
        }
        val needed = sources.values.toSet()
        val missing = (needed + to).filterNot { models.isModelDownloaded(TranslateRemoteModel.Builder(it).build()).await() }
        if (missing.isNotEmpty()) {
            val shown = missing.firstOrNull { it != to } ?: to
            if (!canDownload()) emit(LyricsTranslation.Pending(shown, needsWifi = true))
            while (!canDownload()) delay(15_000)
            emit(LyricsTranslation.Pending(shown, needsWifi = false))
        }
        val done = HashMap<String, String>()
        needed.forEach { from ->
            val client = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build())
            try {
                client.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                sources.filterValues { it == from }.keys.forEachIndexed { i, line ->
                    client.translate(line).await().trim().takeIf { differs(line, it) }?.let { done[line] = it }
                    if (i % 12 == 11) emit(LyricsTranslation.Ready(lines.map { done[it.trim()] }))
                }
            } finally {
                client.close()
            }
        }
        val result = lines.map { done[it.trim()] }
        synchronized(recent) { recent[key] = result }
        emit(LyricsTranslation.Ready(result))
    }.catch { }

    suspend fun packs(): List<String> = runCatching {
        models.getDownloadedModels(TranslateRemoteModel::class.java).await().map { it.language }.sorted()
    }.getOrDefault(emptyList())

    suspend fun deletePack(language: String): Boolean = runCatching {
        models.deleteDownloadedModel(TranslateRemoteModel.Builder(language).build()).await()
        synchronized(recent) { recent.clear() }
    }.isSuccess

    private fun supported(tag: String): String? =
        tag.takeUnless { it == "und" || "-" in it }?.let { TranslateLanguage.fromLanguageTag(if (it == "in") "id" else if (it == "iw") "he" else it) }

    private fun differs(original: String, translated: String): Boolean {
        fun bare(s: String) = s.lowercase().filter(Char::isLetterOrDigit)
        return translated.isNotBlank() && bare(original) != bare(translated)
    }

    private companion object {
        const val SAMPLE = 4000
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
