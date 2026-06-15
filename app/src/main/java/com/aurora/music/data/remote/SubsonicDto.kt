package com.aurora.music.data.remote

import com.google.gson.annotations.SerializedName

data class SubsonicEnvelope(
    @SerializedName("subsonic-response") val response: SubsonicResponse,
)

data class SubsonicResponse(
    val status: String = "failed",
    val version: String = "",
    val type: String? = null,
    val serverVersion: String? = null,
    val error: SubsonicError? = null,

    val albumList2: AlbumListDto? = null,
    val album: AlbumDto? = null,
    val song: SongDto? = null,
    val artists: ArtistsDto? = null,
    val artist: ArtistDto? = null,
    val playlists: PlaylistsDto? = null,
    val playlist: PlaylistDto? = null,
    val starred2: Starred2Dto? = null,
    val searchResult3: SearchResult3Dto? = null,
    val randomSongs: RandomSongsDto? = null,
    val similarSongs2: SimilarSongs2Dto? = null,
    val lyricsList: LyricsListDto? = null,
    val musicFolders: MusicFoldersDto? = null,
    val indexes: IndexesDto? = null,
    val directory: DirectoryDto? = null,
) {
    val isOk: Boolean get() = status == "ok"
}

data class LyricsListDto(val structuredLyrics: List<StructuredLyricsDto> = emptyList())
data class StructuredLyricsDto(
    val lang: String? = null,
    val synced: Boolean = false,
    val line: List<LyricLineDto> = emptyList(),
)
data class LyricLineDto(val start: Long? = null, val value: String = "")

data class SubsonicError(val code: Int = 0, val message: String = "")

data class AlbumListDto(val album: List<AlbumDto> = emptyList())

data class AlbumDto(
    val id: String = "",
    val name: String = "",
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val year: Int = 0,
    val genre: String? = null,
    val displayArtist: String? = null,
    val playCount: Int = 0,
    val song: List<SongDto> = emptyList(),
)

data class SongDto(
    val id: String = "",
    val parent: String? = null,
    val title: String = "",
    val album: String? = null,
    val artist: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val track: Int = 0,
    val year: Int = 0,
    val genre: String? = null,
    val coverArt: String? = null,
    val duration: Int = 0,
    val bitRate: Int = 0,
    val bitDepth: Int = 0,
    val samplingRate: Int = 0,
    val channelCount: Int = 0,
    val suffix: String? = null,
    val contentType: String? = null,
    val starred: String? = null,
    val explicitStatus: String? = null,
    val replayGain: ReplayGainDto? = null,
    val isDir: Boolean = false,           // primitive keeps it gson-safe
    val path: String? = null,
)

data class MusicFoldersDto(val musicFolder: List<MusicFolderDto> = emptyList())
data class MusicFolderDto(val id: String = "", val name: String? = null)
data class IndexesDto(val index: List<DirIndexDto> = emptyList(), val child: List<SongDto> = emptyList())
data class DirIndexDto(val name: String = "", val artist: List<DirEntryDto> = emptyList())
data class DirEntryDto(val id: String = "", val name: String = "")
data class DirectoryDto(val id: String = "", val name: String = "", val child: List<SongDto> = emptyList())

data class ReplayGainDto(
    val trackGain: Double? = null,
    val albumGain: Double? = null,
    val trackPeak: Double? = null,
    val albumPeak: Double? = null,
)

data class ArtistsDto(val index: List<ArtistIndexDto> = emptyList())
data class ArtistIndexDto(val name: String = "", val artist: List<ArtistDto> = emptyList())

data class ArtistDto(
    val id: String = "",
    val name: String = "",
    val coverArt: String? = null,
    val artistImageUrl: String? = null,
    val albumCount: Int = 0,
    val album: List<AlbumDto> = emptyList(),
)

data class PlaylistsDto(val playlist: List<PlaylistDto> = emptyList())
data class PlaylistDto(
    val id: String = "",
    val name: String = "",
    val comment: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val coverArt: String? = null,
    val owner: String? = null,
    val entry: List<SongDto> = emptyList(),
)

data class Starred2Dto(
    val song: List<SongDto> = emptyList(),
    val album: List<AlbumDto> = emptyList(),
    val artist: List<ArtistDto> = emptyList(),
)

data class SearchResult3Dto(
    val artist: List<ArtistDto> = emptyList(),
    val album: List<AlbumDto> = emptyList(),
    val song: List<SongDto> = emptyList(),
)

data class RandomSongsDto(val song: List<SongDto> = emptyList())
data class SimilarSongs2Dto(val song: List<SongDto> = emptyList())
