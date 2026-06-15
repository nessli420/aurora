package com.aurora.music.data.remote

// pascalcase property names match jellyfin json keys directly so gson maps by identity without @SerializedName

data class AuthRequest(val Username: String, val Pw: String)

data class AuthResult(val AccessToken: String? = null, val User: JellyUser? = null)
data class JellyUser(val Id: String? = null, val Name: String? = null)

data class ItemsResult(
    val Items: List<BaseItemDto> = emptyList(),
    val TotalRecordCount: Int = 0,
)

data class NameIdPair(val Id: String? = null, val Name: String? = null)
data class UserDataDto(val IsFavorite: Boolean = false, val PlayCount: Int = 0)

data class MediaStreamDto(
    val Type: String? = null,
    val Codec: String? = null,
    val SampleRate: Int? = null,
    val BitDepth: Int? = null,
    val BitRate: Int? = null,
    val Channels: Int? = null,
)

data class MediaSourceDto(
    val Bitrate: Int? = null,
    val Container: String? = null,
    val MediaStreams: List<MediaStreamDto>? = null,
)

data class BaseItemDto(
    val Id: String = "",
    val Name: String? = null,
    val Type: String? = null,
    val Album: String? = null,
    val AlbumId: String? = null,
    val AlbumArtist: String? = null,
    val Artists: List<String>? = null,
    val ArtistItems: List<NameIdPair>? = null,
    val AlbumArtists: List<NameIdPair>? = null,
    val RunTimeTicks: Long? = null,      // 10,000,000 ticks per second
    val ProductionYear: Int? = null,
    val IndexNumber: Int? = null,
    val ParentIndexNumber: Int? = null,
    val ImageTags: Map<String, String>? = null,
    val AlbumPrimaryImageTag: String? = null,
    val ChildCount: Int? = null,
    val UserData: UserDataDto? = null,
    val MediaSources: List<MediaSourceDto>? = null,
    val IsFolder: Boolean? = null,
    val CollectionType: String? = null,
    val Path: String? = null,            // needs Fields=Path
)

data class CreatePlaylistRequest(
    val Name: String,
    val UserId: String,
    val Ids: List<String> = emptyList(),
    val MediaType: String = "Audio",
)

data class CreatePlaylistResult(val Id: String? = null)

data class JellyLyricsResult(val Lyrics: List<JellyLyricLine>? = null)
data class JellyLyricLine(val Text: String? = null, val Start: Long? = null)  // start in ticks
