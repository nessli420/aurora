package com.aurora.music.data

import com.aurora.music.data.rules.RuleSource
import com.aurora.music.model.Song
import java.io.File

class SourceLocalizer(
    private val localSession: Session,
    private val downloads: DownloadManager,
    private val preferLocal: () -> Boolean,
    private val priority: () -> List<String>,
    private val isLocalUrl: (String) -> Boolean,
    private val findLocal: (artist: String, title: String, durationSec: Int) -> Song?,
) {
    // rewrites only streamUrl/metadata id stays the server's so server features keep working
    fun localize(song: Song): Song {
        if (!preferLocal()) return song
        val alreadyLocal = isLocalUrl(song.streamUrl)
        for (tier in priority()) {
            when (tier) {
                "local" -> if (!alreadyLocal) {
                    findLocal(song.artist, song.title, song.durationSec)?.let { return fromFile(song, it) }
                }
                "downloaded" -> downloads.getByOriginalId(song.id, song.playbackSource?.providerId)
                    ?.let { return fromDownload(song, it.toSong(downloads.fileUri)) }
                "stream" -> return song
            }
        }
        return song
    }

    // carry the file's replaygain + format so loudness/ui match what actually plays
    fun fromFile(song: Song, local: Song): Song = song.copy(
        streamUrl = local.streamUrl,
        artworkUrl = song.artworkUrl.ifBlank { local.artworkUrl },
        replayGainTrack = local.replayGainTrack,
        replayGainAlbum = local.replayGainAlbum,
        suffix = local.suffix,
        bitrateKbps = local.bitrateKbps,
        sampleRateHz = local.sampleRateHz,
        bitDepth = local.bitDepth,
        path = local.path,
        playbackSource = PlaybackSourceIdentity.fromSession(localSession, local.albumId, RuleSource.LOCAL_FILE),
    )

    fun fromDownload(song: Song, local: Song): Song = song.copy(
        streamUrl = local.streamUrl,
        artworkUrl = local.artworkUrl.ifBlank { song.artworkUrl },
        suffix = local.suffix,
        bitrateKbps = local.bitrateKbps,
        sampleRateHz = local.sampleRateHz,
        bitDepth = local.bitDepth,
        playbackSource = local.playbackSource,
    )

    fun mergedBackend(
        session: Session,
        mergeKeys: Set<String>,
        saved: List<Session>,
        local: MediaBackend,
        eligible: (Session) -> Boolean = { true },
        build: (Session) -> MediaBackend?,
    ): MergedBackend {
        // empty = all eligible servers MERGE_NONE sentinel = local files only
        val serverSessions = if (mergeKeys == setOf(MERGE_NONE)) emptyList() else (saved + session)
            .filter { eligible(it) && it.type.supportsMergedLibrary && it.type != ServerType.LOCAL }
            .filter { mergeKeys.isEmpty() || it.accountKey() in mergeKeys }
            .distinctBy { it.accountKey() }
        val sources = buildList {
            add(local)
            serverSessions.forEach { build(it)?.let(::add) }
        }
        return MergedBackend(sources, session,
            priority = { if (preferLocal()) priority() else listOf("stream", "local", "downloaded") },
            downloads = { downloads.downloads.value.values.filter { File(it.audioPath).isFile }.map { it.toSong(downloads.fileUri) } })
    }
}
