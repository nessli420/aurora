package com.aurora.music.data

import com.aurora.music.model.Song

data class HiddenSong(val id: String? = "", val title: String? = "", val artist: String? = "", val album: String? = "", val durationSec: Int? = 0) {
    fun toSong() = Song(id.orEmpty(), title.orEmpty(), artist.orEmpty(), album.orEmpty(), "", durationSec ?: 0)

    companion object {
        fun of(song: Song) = HiddenSong(song.id, song.title, song.artist, song.album, song.durationSec)
    }
}

// hidden as recordings, so a song hidden on one source stays out of every other source's playback too
class HiddenSongs(val songs: List<HiddenSong>) {
    private val ids: Set<String> = songs.mapNotNullTo(HashSet()) { it.id?.takeIf(String::isNotEmpty) }
    private val byTitle = HashMap<String, List<Pair<Song, HiddenSong>>>().also { index ->
        songs.forEach { h -> recordingVariants(h.toSong()).forEach { v -> index.merge(recordingKey(v.title), listOf(v to h)) { a, b -> a + b } } }
    }

    private fun matches(song: Song): Sequence<HiddenSong> = if (byTitle.isEmpty()) emptySequence() else recordingVariants(song).asSequence()
        .flatMap { v -> byTitle[recordingKey(v.title)].orEmpty().asSequence().filter { sameRecording(v, it.first) }.map { it.second } }

    fun hides(song: Song): Boolean = song.id in ids || matches(song).any()

    // every entry that hides this song, whichever source it was hidden on
    fun entriesFor(song: Song): List<HiddenSong> = (songs.filter { it.id == song.id } + matches(song)).distinct()

    fun visible(list: List<Song>): List<Song> = if (songs.isEmpty()) list else list.filterNot(::hides)

    companion object {
        val NONE = HiddenSongs(emptyList())
    }
}
