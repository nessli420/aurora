package com.aurora.music.data.remote

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Subsonic / OpenSubsonic REST API (Navidrome). Auth params (u, t, s, v, c, f) are
 * injected by an OkHttp interceptor, so endpoints only declare their own params.
 */
interface SubsonicApi {

    @GET("rest/ping.view")
    suspend fun ping(): SubsonicEnvelope

    @GET("rest/getAlbumList2.view")
    suspend fun getAlbumList2(
        @Query("type") type: String,
        @Query("size") size: Int = 20,
        @Query("offset") offset: Int = 0,
    ): SubsonicEnvelope

    @GET("rest/getAlbum.view")
    suspend fun getAlbum(@Query("id") id: String): SubsonicEnvelope

    @GET("rest/getSong.view")
    suspend fun getSong(@Query("id") id: String): SubsonicEnvelope

    @GET("rest/getLyricsBySongId.view")
    suspend fun getLyricsBySongId(@Query("id") id: String): SubsonicEnvelope

    @GET("rest/getArtists.view")
    suspend fun getArtists(): SubsonicEnvelope

    @GET("rest/getMusicFolders.view")
    suspend fun getMusicFolders(): SubsonicEnvelope

    @GET("rest/getIndexes.view")
    suspend fun getIndexes(@Query("musicFolderId") musicFolderId: String? = null): SubsonicEnvelope

    @GET("rest/getMusicDirectory.view")
    suspend fun getMusicDirectory(@Query("id") id: String): SubsonicEnvelope

    @GET("rest/getArtist.view")
    suspend fun getArtist(@Query("id") id: String): SubsonicEnvelope

    @GET("rest/getPlaylists.view")
    suspend fun getPlaylists(): SubsonicEnvelope

    @GET("rest/getPlaylist.view")
    suspend fun getPlaylist(@Query("id") id: String): SubsonicEnvelope

    @GET("rest/createPlaylist.view")
    suspend fun createPlaylist(@Query("name") name: String): SubsonicEnvelope

    @GET("rest/updatePlaylist.view")
    suspend fun updatePlaylist(
        @Query("playlistId") id: String,
        @Query("name") name: String? = null,
        @Query("comment") comment: String? = null,
        @Query("songIdToAdd") songIdToAdd: List<String>? = null,
    ): SubsonicEnvelope

    @GET("rest/deletePlaylist.view")
    suspend fun deletePlaylist(@Query("id") id: String): SubsonicEnvelope

    @GET("rest/getStarred2.view")
    suspend fun getStarred2(): SubsonicEnvelope

    @GET("rest/getRandomSongs.view")
    suspend fun getRandomSongs(@Query("size") size: Int = 50): SubsonicEnvelope

    @GET("rest/getSimilarSongs2.view")
    suspend fun getSimilarSongs2(@Query("id") id: String, @Query("count") count: Int = 30): SubsonicEnvelope

    @GET("rest/search3.view")
    suspend fun search3(
        @Query("query") query: String,
        @Query("artistCount") artistCount: Int = 10,
        @Query("albumCount") albumCount: Int = 10,
        @Query("songCount") songCount: Int = 30,
    ): SubsonicEnvelope

    @GET("rest/star.view")
    suspend fun star(
        @Query("id") id: String? = null,
        @Query("albumId") albumId: String? = null,
        @Query("artistId") artistId: String? = null,
    ): SubsonicEnvelope

    @GET("rest/unstar.view")
    suspend fun unstar(
        @Query("id") id: String? = null,
        @Query("albumId") albumId: String? = null,
        @Query("artistId") artistId: String? = null,
    ): SubsonicEnvelope

    @GET("rest/scrobble.view")
    suspend fun scrobble(
        @Query("id") id: String,
        @Query("submission") submission: Boolean = true,
    ): SubsonicEnvelope
}
