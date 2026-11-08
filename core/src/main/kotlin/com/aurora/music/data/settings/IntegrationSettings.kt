package com.aurora.music.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.aurora.music.data.*
import com.aurora.music.data.SettingsKeys as Keys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

interface IntegrationSettings {
    val discord: Flow<DiscordAccount>
    val lastfm: Flow<LastfmAccount>
    val lastfmKeys: Flow<Pair<String, String>>
    val lastfmPendingToken: Flow<String>
    val lastfmArtistRules: Flow<List<ScrobbleArtistRule>>
    val listenBrainz: Flow<ListenBrainzAccount>
    val acoustIdKey: Flow<String>
    val squigBaseUrl: Flow<String>
    suspend fun setSquigBaseUrl(v: String): Preferences
    val squigTarget: Flow<String>
    suspend fun setSquigTarget(v: String): Preferences
    suspend fun setAcoustIdKey(v: String): Preferences
    suspend fun saveLastfm(sessionKey: String, username: String, imageUrl: String): Preferences
    suspend fun clearLastfm(): Preferences
    suspend fun setLastfmEnabled(v: Boolean): Preferences
    suspend fun setLastfmKeys(apiKey: String, secret: String): Preferences
    suspend fun setLastfmPendingToken(token: String?): Preferences
    suspend fun setLastfmArtistRules(rules: List<ScrobbleArtistRule>): Preferences
    suspend fun saveListenBrainz(token: String, username: String): Preferences
    suspend fun clearListenBrainz(): Preferences
    suspend fun setListenBrainzEnabled(v: Boolean): Preferences
    suspend fun saveDiscord(token: String, username: String): Preferences
    suspend fun clearDiscord(): Preferences
    suspend fun setDiscordEnabled(v: Boolean): Preferences
    suspend fun setDiscordImgur(v: String): Preferences
    suspend fun setDiscordShowAlbum(v: Boolean): Preferences
    suspend fun setDiscordActivityName(v: String): Preferences
    suspend fun setDiscordAppId(v: String): Preferences
}

internal class IntegrationSettingsStore(private val dataStore: DataStore<Preferences>) : IntegrationSettings {
    override val discord: Flow<DiscordAccount> = dataStore.data.map { p ->
        DiscordAccount(
            token = p[Keys.DISCORD_TOKEN].orEmpty(),
            username = p[Keys.DISCORD_USER].orEmpty(),
            enabled = p[Keys.DISCORD_ENABLED] ?: true,
            imgurClientId = p[Keys.DISCORD_IMGUR].orEmpty(),
            appId = p[Keys.DISCORD_APP_ID].orEmpty(),
            showAlbum = p[Keys.DISCORD_SHOW_ALBUM] ?: true,
            activityName = p[Keys.DISCORD_ACTIVITY_NAME] ?: "aurora",
        )
    }.distinctUntilChanged()

    override val lastfm: Flow<LastfmAccount> = dataStore.data.map { p ->
        LastfmAccount(
            sessionKey = p[Keys.LASTFM_SK].orEmpty(),
            username = p[Keys.LASTFM_USER].orEmpty(),
            imageUrl = p[Keys.LASTFM_IMAGE].orEmpty(),
            enabled = p[Keys.LASTFM_ENABLED] ?: true,
        )
    }.distinctUntilChanged()

    override val lastfmKeys: Flow<Pair<String, String>> = dataStore.data.map { p ->
        (p[Keys.LASTFM_API_KEY].orEmpty()) to (p[Keys.LASTFM_SECRET].orEmpty())
    }.distinctUntilChanged()

    override val lastfmPendingToken: Flow<String> = dataStore.data.map { it[Keys.LASTFM_PENDING_TOKEN].orEmpty() }.distinctUntilChanged()

    override val lastfmArtistRules: Flow<List<ScrobbleArtistRule>> = dataStore.data
        .map { it[Keys.LASTFM_ARTIST_RULES] }.distinctUntilChanged()
        .map { runCatching { ScrobbleArtistRulesCodec.decode(it) }.getOrDefault(emptyList()) }.distinctUntilChanged()

    override val listenBrainz: Flow<ListenBrainzAccount> = dataStore.data.map { p ->
        ListenBrainzAccount(
            token = p[Keys.LISTENBRAINZ_TOKEN].orEmpty(),
            username = p[Keys.LISTENBRAINZ_USER].orEmpty(),
            enabled = p[Keys.LISTENBRAINZ_ENABLED] ?: true,
        )
    }.distinctUntilChanged()

    override val acoustIdKey: Flow<String> = dataStore.data.map { it[Keys.ACOUSTID_KEY] ?: "" }.distinctUntilChanged()

    override val squigBaseUrl: Flow<String> = dataStore.data.map { it[Keys.SQUIG_BASE]?.takeIf { u -> u.isNotBlank() } ?: DEFAULT_SQUIG_BASE }.distinctUntilChanged()
    override suspend fun setSquigBaseUrl(v: String) = dataStore.edit { it[Keys.SQUIG_BASE] = v.trim().trimEnd('/') }

    override val squigTarget: Flow<String> = dataStore.data.map { it[Keys.SQUIG_TARGET]?.takeIf { t -> t.isNotBlank() } ?: DEFAULT_SQUIG_TARGET }.distinctUntilChanged()
    override suspend fun setSquigTarget(v: String) = dataStore.edit { it[Keys.SQUIG_TARGET] = v.trim() }

    override suspend fun setAcoustIdKey(v: String) = dataStore.edit { it[Keys.ACOUSTID_KEY] = v.trim() }

    override suspend fun saveLastfm(sessionKey: String, username: String, imageUrl: String) = dataStore.edit { p ->
        p[Keys.LASTFM_SK] = sessionKey
        p[Keys.LASTFM_USER] = username
        p[Keys.LASTFM_IMAGE] = imageUrl
        p[Keys.LASTFM_ENABLED] = true
        p.remove(Keys.LASTFM_PENDING_TOKEN)
    }

    override suspend fun clearLastfm() = dataStore.edit { p ->
        p.remove(Keys.LASTFM_SK); p.remove(Keys.LASTFM_USER); p.remove(Keys.LASTFM_IMAGE); p.remove(Keys.LASTFM_PENDING_TOKEN)
    }

    override suspend fun setLastfmEnabled(v: Boolean) = dataStore.edit { it[Keys.LASTFM_ENABLED] = v }

    override suspend fun setLastfmKeys(apiKey: String, secret: String) = dataStore.edit { p ->
        p[Keys.LASTFM_API_KEY] = apiKey.trim()
        p[Keys.LASTFM_SECRET] = secret.trim()
    }

    override suspend fun setLastfmPendingToken(token: String?) = dataStore.edit { p ->
        if (token.isNullOrBlank()) p.remove(Keys.LASTFM_PENDING_TOKEN) else p[Keys.LASTFM_PENDING_TOKEN] = token
    }

    override suspend fun setLastfmArtistRules(rules: List<ScrobbleArtistRule>) = dataStore.edit { p ->
        p[Keys.LASTFM_ARTIST_RULES] = ScrobbleArtistRulesCodec.encode(rules)
    }

    override suspend fun saveListenBrainz(token: String, username: String) = dataStore.edit { p ->
        p[Keys.LISTENBRAINZ_TOKEN] = token
        p[Keys.LISTENBRAINZ_USER] = username
        p[Keys.LISTENBRAINZ_ENABLED] = true
    }
    override suspend fun clearListenBrainz() = dataStore.edit { p ->
        p.remove(Keys.LISTENBRAINZ_TOKEN); p.remove(Keys.LISTENBRAINZ_USER)
    }
    override suspend fun setListenBrainzEnabled(v: Boolean) = dataStore.edit { it[Keys.LISTENBRAINZ_ENABLED] = v }

    override suspend fun saveDiscord(token: String, username: String) = dataStore.edit { p ->
        p[Keys.DISCORD_TOKEN] = token
        p[Keys.DISCORD_USER] = username
        p[Keys.DISCORD_ENABLED] = true
    }

    override suspend fun clearDiscord() = dataStore.edit { p ->
        p.remove(Keys.DISCORD_TOKEN); p.remove(Keys.DISCORD_USER)
    }

    override suspend fun setDiscordEnabled(v: Boolean) = dataStore.edit { it[Keys.DISCORD_ENABLED] = v }
    override suspend fun setDiscordImgur(v: String) = dataStore.edit { it[Keys.DISCORD_IMGUR] = v.trim() }
    override suspend fun setDiscordShowAlbum(v: Boolean) = dataStore.edit { it[Keys.DISCORD_SHOW_ALBUM] = v }
    override suspend fun setDiscordActivityName(v: String) = dataStore.edit { it[Keys.DISCORD_ACTIVITY_NAME] = v }
    override suspend fun setDiscordAppId(v: String) = dataStore.edit { it[Keys.DISCORD_APP_ID] = v.trim() }
}
