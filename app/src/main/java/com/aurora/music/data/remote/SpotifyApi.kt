package com.aurora.music.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

// everything nullable for gson safety

data class SpImage(val url: String? = null, val height: Int = 0, val width: Int = 0)
data class SpFollowers(val total: Long = 0)
data class SpArtistRef(val id: String? = null, val name: String? = null)
data class SpOwner(@SerializedName("display_name") val displayName: String? = null, val id: String? = null)

data class SpAlbumRef(
    val id: String? = null,
    val name: String? = null,
    val images: List<SpImage>? = null,
    @SerializedName("release_date") val releaseDate: String? = null,
    @SerializedName("total_tracks") val totalTracks: Int = 0,
    val artists: List<SpArtistRef>? = null,
)

data class SpTrack(
    val id: String? = null,
    val name: String? = null,
    val artists: List<SpArtistRef>? = null,
    val album: SpAlbumRef? = null,
    @SerializedName("duration_ms") val durationMs: Long = 0,
    val explicit: Boolean = false,
)

data class SpArtist(
    val id: String? = null,
    val name: String? = null,
    val images: List<SpImage>? = null,
    val followers: SpFollowers? = null,
)

data class SpPlaylist(
    val id: String? = null,
    val name: String? = null,
    val description: String? = null,
    val owner: SpOwner? = null,
    val images: List<SpImage>? = null,
    val tracks: SpTracksRef? = null,
    // /me/playlists returns the track-count reference under items not tracks
    val items: SpTracksRef? = null,
)
data class SpTracksRef(val total: Int = 0)

data class SpAlbum(
    val id: String? = null,
    val name: String? = null,
    val artists: List<SpArtistRef>? = null,
    val images: List<SpImage>? = null,
    @SerializedName("release_date") val releaseDate: String? = null,
    @SerializedName("total_tracks") val totalTracks: Int = 0,
    val tracks: SpPaging<SpTrack>? = null,
)

data class SpPaging<T>(val items: List<T>? = null, val next: String? = null, val total: Int = 0)

// newer format embeds track paging under items not tracks and each entry track under item not track read both
data class SpFullPlaylist(
    val id: String? = null,
    val name: String? = null,
    val description: String? = null,
    val owner: SpOwner? = null,
    val images: List<SpImage>? = null,
    val items: SpEmbeddedTracks? = null,
    val tracks: SpEmbeddedTracks? = null,
)
data class SpEmbeddedTracks(val items: List<SpEmbeddedItem>? = null, val total: Int = 0, val next: String? = null)
// track is under item new or track legacy
data class SpEmbeddedItem(val item: SpTrack? = null, val track: SpTrack? = null)

data class SpMe(val id: String? = null, @SerializedName("display_name") val displayName: String? = null, val images: List<SpImage>? = null, val country: String? = null)
data class SpSavedTrack(val track: SpTrack? = null)
data class SpSavedAlbum(val album: SpAlbum? = null)
data class SpPlaylistItem(val track: SpTrack? = null)
data class SpPlayHistory(val track: SpTrack? = null)
data class SpFollowingArtists(val artists: SpPaging<SpArtist>? = null)
data class SpRecommendations(val tracks: List<SpTrack>? = null)
data class SpNewReleases(val albums: SpPaging<SpAlbumRef>? = null)
data class SpArtistTop(val tracks: List<SpTrack>? = null)
data class SpRecentlyPlayed(val items: List<SpPlayHistory>? = null)
data class SpTracksResponse(val tracks: List<SpTrack?>? = null)

data class SpSearch(
    val tracks: SpPaging<SpTrack>? = null,
    val albums: SpPaging<SpAlbumRef>? = null,
    val artists: SpPaging<SpArtist>? = null,
    val playlists: SpPaging<SpPlaylist>? = null,
)

data class CreatePlaylistBody(val name: String, val description: String = "", val public: Boolean = false)
data class ChangeDetailsBody(val name: String? = null, val description: String? = null)
data class AddTracksBody(val uris: List<String>)
data class TrackUri(val uri: String)
data class RemoveTracksBody(val tracks: List<TrackUri>)

interface SpotifyApi {
    @GET("v1/me")
    suspend fun me(): SpMe

    @GET("v1/me/tracks")
    suspend fun savedTracks(@Query("limit") limit: Int = 50, @Query("offset") offset: Int = 0): SpPaging<SpSavedTrack>

    @GET("v1/me/tracks/contains")
    suspend fun tracksContains(@Query("ids") ids: String): List<Boolean>

    @GET("v1/me/playlists")
    suspend fun myPlaylists(@Query("limit") limit: Int = 50, @Query("offset") offset: Int = 0): SpPaging<SpPlaylist>

    @GET("v1/me/albums")
    suspend fun savedAlbums(@Query("limit") limit: Int = 50, @Query("offset") offset: Int = 0): SpPaging<SpSavedAlbum>

    @GET("v1/me/following")
    suspend fun followedArtists(@Query("type") type: String = "artist", @Query("limit") limit: Int = 50): SpFollowingArtists

    @GET("v1/me/top/tracks")
    suspend fun topTracks(@Query("limit") limit: Int = 50, @Query("time_range") range: String = "medium_term"): SpPaging<SpTrack>

    @GET("v1/me/top/artists")
    suspend fun topArtists(@Query("limit") limit: Int = 50): SpPaging<SpArtist>

    @GET("v1/me/player/recently-played")
    suspend fun recentlyPlayed(@Query("limit") limit: Int = 50): SpRecentlyPlayed

    @GET("v1/browse/new-releases")
    suspend fun newReleases(@Query("limit") limit: Int = 30): SpNewReleases

    @GET("v1/recommendations")
    suspend fun recommendations(@Query("seed_tracks") seedTracks: String, @Query("limit") limit: Int = 30): SpRecommendations

    @GET("v1/search")
    suspend fun search(
        @Query("q") q: String,
        @Query("type") type: String = "track,album,artist,playlist",
        @Query("limit") limit: Int = 20,
        @Query("market") market: String? = null,
    ): SpSearch

    @GET("v1/albums/{id}")
    suspend fun album(@Path("id") id: String): SpAlbum

    @GET("v1/albums/{id}/tracks")
    suspend fun albumTracks(@Path("id") id: String, @Query("offset") offset: Int = 0, @Query("limit") limit: Int = 50): SpPaging<SpTrack>

    @GET("v1/artists/{id}")
    suspend fun artist(@Path("id") id: String): SpArtist

    @GET("v1/artists/{id}/top-tracks")
    suspend fun artistTopTracks(@Path("id") id: String, @Query("market") market: String = "from_token"): SpArtistTop

    @GET("v1/artists/{id}/albums")
    suspend fun artistAlbums(@Path("id") id: String, @Query("limit") limit: Int = 30, @Query("include_groups") groups: String = "album,single"): SpPaging<SpAlbumRef>

    @GET("v1/playlists/{id}")
    suspend fun playlist(@Path("id") id: String): SpFullPlaylist

    @GET("v1/playlists/{id}/tracks")
    suspend fun playlistTracks(@Path("id") id: String, @Query("limit") limit: Int = 100, @Query("offset") offset: Int = 0): SpPaging<SpPlaylistItem>

    // works for owned and followed public playlists unlike the 403-restricted /tracks
    @GET("v1/playlists/{id}/items")
    suspend fun playlistItems(@Path("id") id: String, @Query("offset") offset: Int = 0, @Query("limit") limit: Int = 50): SpEmbeddedTracks

    @GET("v1/tracks/{id}")
    suspend fun track(@Path("id") id: String): SpTrack

    @GET("v1/tracks")
    suspend fun tracks(@Query("ids") ids: String): SpTracksResponse

    @PUT("v1/me/tracks")
    suspend fun saveTracks(@Query("ids") ids: String): retrofit2.Response<Unit>

    @DELETE("v1/me/tracks")
    suspend fun removeTracks(@Query("ids") ids: String): retrofit2.Response<Unit>

    @PUT("v1/me/following")
    suspend fun followArtists(@Query("type") type: String, @Query("ids") ids: String): retrofit2.Response<Unit>

    @DELETE("v1/me/following")
    suspend fun unfollowArtists(@Query("type") type: String, @Query("ids") ids: String): retrofit2.Response<Unit>

    @PUT("v1/albums")
    suspend fun saveAlbums(@Query("ids") ids: String): retrofit2.Response<Unit>

    @DELETE("v1/albums")
    suspend fun removeAlbums(@Query("ids") ids: String): retrofit2.Response<Unit>

    @PUT("v1/playlists/{id}/followers")
    suspend fun followPlaylist(@Path("id") id: String): retrofit2.Response<Unit>

    @DELETE("v1/playlists/{id}/followers")
    suspend fun unfollowPlaylist(@Path("id") id: String): retrofit2.Response<Unit>

    @POST("v1/users/{userId}/playlists")
    suspend fun createPlaylist(@Path("userId") userId: String, @Body body: CreatePlaylistBody): SpPlaylist

    @PUT("v1/playlists/{id}")
    suspend fun changePlaylistDetails(@Path("id") id: String, @Body body: ChangeDetailsBody): retrofit2.Response<Unit>

    @POST("v1/playlists/{id}/tracks")
    suspend fun addToPlaylist(@Path("id") id: String, @Body body: AddTracksBody): retrofit2.Response<Unit>

    @HTTP(method = "DELETE", path = "v1/playlists/{id}/tracks", hasBody = true)
    suspend fun removeFromPlaylist(@Path("id") id: String, @Body body: RemoveTracksBody): retrofit2.Response<Unit>
}
