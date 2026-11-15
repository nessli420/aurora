package com.aurora.music.data

import kotlinx.coroutines.CancellationException

// reads a shared link or an exported file, then matches it against your sources and the catalogues
class PlaylistImporter(
    private val repository: MusicRepository,
    private val links: PlaylistLinks,
    // youtube music and soundcloud as you added them, or signed out when you have not
    private val catalogue: suspend (ServerType) -> MediaBackend?,
) {
    fun recognises(input: String) = links.parse(input) != null

    suspend fun fromLink(input: String, fallbackName: String, onProgress: (ImportProgress) -> Unit = {}): ImportResult {
        onProgress(ImportProgress.Reading)
        val link = links.parse(input) ?: throw ImportException(ImportFailure.UNSUPPORTED)
        val list = when (link.service) {
            PlaylistLinks.Service.YOUTUBE -> fromCatalogue(ServerType.YOUTUBE_MUSIC) { it.detail(link.kind, link.id) }
            PlaylistLinks.Service.SOUNDCLOUD -> fromCatalogue(ServerType.SOUNDCLOUD) { (it as? SoundCloudBackend)?.resolvePlaylist(link.url) }
            // apple's api reads the whole list; its page, which holds only the first part, stays as the fallback
            PlaylistLinks.Service.APPLE_MUSIC -> try { fromCatalogue(ServerType.APPLE_MUSIC) { it.detail(link.kind, link.id) } }
                catch (_: ImportException) { attempt { links.read(link) } }
            else -> attempt { links.read(link) }
        }
        val limited = link.service == PlaylistLinks.Service.SPOTIFY && list.tracks.size >= SPOTIFY_LIMIT
        return import(list, fallbackName, onProgress).copy(limited = limited)
    }

    suspend fun fromFile(fileName: String, bytes: ByteArray, fallbackName: String, onProgress: (ImportProgress) -> Unit = {}): ImportResult {
        onProgress(ImportProgress.Reading)
        return import(attempt { PlaylistFiles.read(fileName, bytes) }, fallbackName, onProgress)
    }

    // a list from a catalogue Aurora plays keeps that service's own songs to fall back on
    private suspend fun fromCatalogue(type: ServerType, read: suspend (MediaBackend) -> DetailData?): ImportedPlaylist {
        val source = catalogue(type) ?: throw ImportException(ImportFailure.UNREADABLE)
        val detail = attempt { read(source) } ?: throw ImportException(ImportFailure.UNREADABLE)
        val kind = ImportSource.of(type)
        return ImportedPlaylist(detail.info.title, detail.tracks.map {
            ImportTrack(it.title, it.artist, it.album, it.durationSec, it.inNamespace(source.session), kind)
        })
    }

    private suspend fun import(list: ImportedPlaylist, fallbackName: String, onProgress: (ImportProgress) -> Unit): ImportResult {
        if (list.tracks.isEmpty()) throw ImportException(ImportFailure.EMPTY)
        val extra = listOf(ServerType.YOUTUBE_MUSIC, ServerType.SOUNDCLOUD).flatMap { type ->
            val source = try { catalogue(type) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } ?: return@flatMap emptyList()
            source.importStages().map { stage -> ImportStage(stage.source) { q -> stage.search(q).map { it.inNamespace(source.session) } } }
        }
        return repository.importTracks(list.copy(name = list.name.ifBlank { fallbackName }), extra, onProgress)
    }

    private suspend fun <T> attempt(block: suspend () -> T): T = try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: ImportException) { throw e }
        catch (e: Exception) { throw ImportException(ImportFailure.UNREADABLE, e.message, e) }

    private companion object {
        const val SPOTIFY_LIMIT = 100
    }
}
