package com.aurora.music.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.QueryMap

// auth header added by interceptor so endpoints omit it
interface JellyfinApi {

    @GET("System/Info/Public")
    suspend fun publicInfo(): Response<Unit>

    @POST("Users/AuthenticateByName")
    suspend fun authenticate(@Body body: AuthRequest): AuthResult

    @GET("Users/{userId}/Items")
    suspend fun items(@Path("userId") userId: String, @QueryMap params: Map<String, String>): ItemsResult

    @GET("Users/{userId}/Items/{id}")
    suspend fun item(@Path("userId") userId: String, @Path("id") id: String): BaseItemDto

    // raw json so a post update preserves untouched fields
    @GET("Users/{userId}/Items/{id}")
    suspend fun itemRaw(@Path("userId") userId: String, @Path("id") id: String): com.google.gson.JsonObject

    // jellyfin requires the full item object on update
    @POST("Items/{id}")
    suspend fun updateItem(@Path("id") id: String, @Body body: com.google.gson.JsonObject): Response<Unit>

    @GET("Artists")
    suspend fun artists(@QueryMap params: Map<String, String>): ItemsResult

    @GET("Playlists/{id}/Items")
    suspend fun playlistItems(@Path("id") id: String, @QueryMap params: Map<String, String>): ItemsResult

    @GET("Items/{id}/Similar")
    suspend fun similar(@Path("id") id: String, @QueryMap params: Map<String, String>): ItemsResult

    @GET("Audio/{id}/Lyrics")
    suspend fun lyrics(@Path("id") id: String): JellyLyricsResult

    @POST("Users/{userId}/FavoriteItems/{id}")
    suspend fun favorite(@Path("userId") userId: String, @Path("id") id: String): Response<Unit>

    @DELETE("Users/{userId}/FavoriteItems/{id}")
    suspend fun unfavorite(@Path("userId") userId: String, @Path("id") id: String): Response<Unit>

    @POST("Users/{userId}/PlayedItems/{id}")
    suspend fun markPlayed(@Path("userId") userId: String, @Path("id") id: String): Response<Unit>

    @POST("Playlists")
    suspend fun createPlaylist(@Body body: CreatePlaylistRequest): CreatePlaylistResult

    @POST("Playlists/{id}/Items")
    suspend fun addToPlaylist(@Path("id") id: String, @Query("ids") ids: String, @Query("userId") userId: String): Response<Unit>

    @DELETE("Items/{id}")
    suspend fun deleteItem(@Path("id") id: String): Response<Unit>
}
