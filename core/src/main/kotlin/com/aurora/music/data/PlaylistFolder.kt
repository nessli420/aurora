package com.aurora.music.data

// a folder of playlists as spotify keeps them; entries hold its folders and playlist ids in the account's own order
data class PlaylistFolder(val id: String, val name: String, val entries: List<PlaylistFolderEntry> = emptyList()) {
    val folders: List<PlaylistFolder> get() = entries.mapNotNull { (it as? PlaylistFolderEntry.Folder)?.folder }
    val playlistIds: List<String> get() = entries.mapNotNull { (it as? PlaylistFolderEntry.Item)?.playlistId }

    fun find(folderId: String): PlaylistFolder? = if (id == folderId) this else folders.firstNotNullOfOrNull { it.find(folderId) }

    // playlists filed in a folder somewhere below this one, which the level above leaves out
    fun nestedPlaylistIds(): Set<String> = folders.flatMapTo(HashSet()) { it.playlistIds + it.nestedPlaylistIds() }

    // every folder below this one, depth first, with its depth for an indented picker
    fun allFolders(depth: Int = 0): List<Pair<PlaylistFolder, Int>> = folders.flatMap { listOf(it to depth) + it.allFolders(depth + 1) }

    fun parentOf(folderId: String): PlaylistFolder? =
        if (folders.any { it.id == folderId }) this else folders.firstNotNullOfOrNull { it.parentOf(folderId) }

    fun folderOf(playlistId: String): PlaylistFolder? =
        if (playlistId in playlistIds) this else folders.firstNotNullOfOrNull { it.folderOf(playlistId) }

    fun mapIds(map: (String) -> String): PlaylistFolder = copy(id = if (id.isEmpty()) id else map(id), entries = entries.map {
        when (it) {
            is PlaylistFolderEntry.Folder -> PlaylistFolderEntry.Folder(it.folder.mapIds(map))
            is PlaylistFolderEntry.Item -> PlaylistFolderEntry.Item(map(it.playlistId))
        }
    })
}

sealed interface PlaylistFolderEntry {
    data class Folder(val folder: PlaylistFolder) : PlaylistFolderEntry
    data class Item(val playlistId: String) : PlaylistFolderEntry
}
