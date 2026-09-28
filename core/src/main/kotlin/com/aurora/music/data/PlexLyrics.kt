package com.aurora.music.data

import com.aurora.music.data.remote.PlexClient
import com.aurora.music.data.remote.PlexException
import com.aurora.music.model.LyricLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale

internal suspend fun plexLyrics(client: PlexClient, songId: String): Lyrics? {
    val item = client.metadata(songId)?.takeIf { it.type == "track" } ?: return null
    val streams = item.media.orEmpty().flatMap { it.parts.orEmpty() }.flatMap { it.streams.orEmpty() }
        .filter { it.streamType == 4 && !it.key.isNullOrBlank() }
        .map { stream ->
            val format = (stream.format?.takeIf(String::isNotBlank) ?: stream.codec).orEmpty().lowercase(Locale.ROOT)
            stream to format
        }
        .filter { (_, format) -> format in setOf("lrc", "txt", "text", "plain", "text/plain", "") }
        .sortedBy { (_, format) -> if (format == "lrc") 0 else 1 }
        .distinctBy { (stream, _) -> stream.key }
    for ((stream, format) in streams) {
        currentCoroutineContext().ensureActive()
        val text = try {
            client.getText(stream.key.orEmpty()).removePrefix("\uFEFF").trim()
        } catch (e: CancellationException) {
            throw e
        } catch (e: PlexException) {
            if (e.statusCode in setOf(400, 404, 406, 415, 422)) continue else throw e
        } catch (_: IllegalArgumentException) {
            continue
        }
        if (text.isBlank()) continue
        val synced = LyricsRepository.parseLrc(text)
        if (synced.any { it.text.isNotBlank() }) return Lyrics(synced, true, "Plex")
        if (format != "lrc") return Lyrics(text.lines().map { LyricLine(0, it) }, false, "Plex")
    }
    return null
}
