package com.aurora.music.data

import com.aurora.music.model.Song

data class HiddenSong(val id: String? = "", val title: String? = "", val artist: String? = "", val album: String? = "", val durationSec: Int? = 0) {
    fun toSong() = Song(id.orEmpty(), title.orEmpty(), artist.orEmpty(), album.orEmpty(), "", durationSec ?: 0)

    companion object {
        fun of(song: Song) = HiddenSong(song.id, song.title, song.artist, song.album, song.durationSec)
    }
}

// hidden as recordings and blocked artists, so what is hidden on one source stays out of every other source's playback too
class HiddenSongs(val songs: List<HiddenSong>, val artists: List<String> = emptyList(), private val separators: ArtistSeparators = ArtistSeparators()) {
    private val ids: Set<String> = songs.mapNotNullTo(HashSet()) { it.id?.takeIf(String::isNotEmpty) }
    private val byTitle = HashMap<String, List<Pair<Song, HiddenSong>>>().also { index ->
        songs.forEach { h -> recordingVariants(h.toSong()).forEach { v -> index.merge(recordingKey(v.title), listOf(v to h)) { a, b -> a + b } } }
    }
    private val blocked: Map<String, String> = artists.associateBy(::artistKey) - ""
    private val split = separators.splitter()

    private fun matches(song: Song): Sequence<HiddenSong> = if (byTitle.isEmpty()) emptySequence() else recordingVariants(song).asSequence()
        .flatMap { v -> byTitle[recordingKey(v.title)].orEmpty().asSequence().filter { sameRecording(v, it.first) }.map { it.second } }

    fun primaryArtist(credit: String): String = split(credit).first()

    // the whole credit or its first name; an artist featured on someone else's song doesn't count
    fun blockedArtist(song: Song): String? = if (blocked.isEmpty()) null else recordingVariants(song).firstNotNullOfOrNull { v ->
        blocked[artistKey(v.artist)] ?: blocked[artistKey(primaryArtist(v.artist))]
    }

    fun isBlocked(artist: String): Boolean = artistKey(artist) in blocked

    fun hidesSong(song: Song): Boolean = song.id in ids || matches(song).any()

    fun hides(song: Song): Boolean = hidesSong(song) || blockedArtist(song) != null

    // every entry that hides this song, whichever source it was hidden on
    fun entriesFor(song: Song): List<HiddenSong> = (songs.filter { it.id == song.id } + matches(song)).distinct()

    fun visible(list: List<Song>): List<Song> = if (songs.isEmpty() && blocked.isEmpty()) list else list.filterNot(::hides)

    fun onlyArtist(name: String) = HiddenSongs(emptyList(), listOf(name), separators)

    companion object {
        val NONE = HiddenSongs(emptyList())
    }
}
