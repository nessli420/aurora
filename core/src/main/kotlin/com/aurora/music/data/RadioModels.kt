package com.aurora.music.data

import com.aurora.music.data.settings.CollectionSettings
import com.aurora.music.model.Song
import com.aurora.music.util.accentArgbFor
import kotlinx.coroutines.flow.first

// durationSec 0 marks non-seekable live stream
fun RadioStation.toSong(): Song = Song(
    id = "radio:$uuid",
    title = displayName,
    artist = genre,
    album = "Internet radio",
    artworkUrl = faviconUrl.orEmpty(),
    durationSec = 0,
    streamUrl = streamUrl.orEmpty(),
    accentArgb = accentArgbFor("radio:$uuid"),
)

fun Song.isRadio(): Boolean = id.startsWith("radio:")

// what a playing station's heart saves
fun Song.toStation(): RadioStation = id.removePrefix("radio:").let { uuid ->
    RadioStation(uuid = uuid, name = title, streamUrl = streamUrl, faviconUrl = artworkUrl, tags = artist, custom = uuid.startsWith("custom:"))
}

// the player heart used to file stations among the local library's song likes, where no list showed them
suspend fun moveStationLikes(store: LocalStore, settings: CollectionSettings, lookup: suspend (List<String>) -> List<RadioStation>?) {
    val old = store.likedIds().filter { it.startsWith("radio:") }.map { it.removePrefix("radio:") }
    if (old.isEmpty()) return
    val kept = settings.radioFavorites.first().mapTo(HashSet()) { it.uuid }
    val missing = old.filterNot { it in kept || it.startsWith("custom:") }
    val found = (if (missing.isEmpty()) null else lookup(missing)).orEmpty().filter { it.uuid in missing }.distinctBy { it.uuid }
    found.forEach { settings.saveRadioStation(it) }
    val moved = kept + found.map { it.uuid }
    old.filter { it in moved }.forEach { store.setLiked("radio:$it", false) }
}
