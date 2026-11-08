package com.aurora.music.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aurora.music.data.*
import com.aurora.music.data.SettingsKeys as Keys
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

interface AccountSettings {
    val spotifyClientId: Flow<String>
    val soundcloudClientId: Flow<String>
    val session: Flow<Session?>
    suspend fun saveSession(session: Session)
    val extensionGrants: Flow<String?>
    suspend fun setExtensionGrants(json: String)
    suspend fun updateToken(token: String): Preferences
    suspend fun updateUserImage(url: String): Preferences
    suspend fun clearSession()
    val savedSessions: Flow<List<Session>>
    suspend fun addSavedSession(session: Session): Preferences
    suspend fun removeSavedSession(session: Session): Preferences
    suspend fun setSpotifyClientId(v: String): Preferences
    suspend fun setSoundcloudClientId(v: String)
    val localProfile: Flow<LocalProfile>
    suspend fun setLocalProfile(profile: LocalProfile)
}

internal class AccountSettingsStore(private val dataStore: DataStore<Preferences>) : AccountSettings {
    private val gson = Gson()

    override val spotifyClientId: Flow<String> = dataStore.data.map { it[Keys.SPOTIFY_CLIENT_ID] ?: "" }.distinctUntilChanged()
    override val soundcloudClientId: Flow<String> = dataStore.data.map { it[Keys.SOUNDCLOUD_CLIENT_ID] ?: "" }.distinctUntilChanged()

    override val session: Flow<Session?> = dataStore.data.map { p ->
        val s = Session(
            server = p[Keys.SERVER].orEmpty(),
            username = p[Keys.USERNAME].orEmpty(),
            salt = p[Keys.SALT].orEmpty(),
            token = p[Keys.TOKEN].orEmpty(),
            type = runCatching { ServerType.valueOf(p[Keys.SERVER_TYPE] ?: "SUBSONIC") }.getOrDefault(ServerType.SUBSONIC),
            userId = p[Keys.USER_ID].orEmpty(),
            imageUrl = p[Keys.USER_IMAGE].orEmpty(),
            clientToken = p[Keys.CLIENT_TOKEN].orEmpty(),
            clientVersion = p[Keys.CLIENT_VERSION].orEmpty(),
        )
        if (s.isValid) s else null
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    override suspend fun saveSession(session: Session) {
        dataStore.edit { p ->
            p[Keys.SERVER] = session.server
            p[Keys.USERNAME] = session.username
            p[Keys.SALT] = session.salt
            p[Keys.TOKEN] = session.token
            p[Keys.SERVER_TYPE] = session.type.name
            p[Keys.USER_ID] = session.userId
            p[Keys.USER_IMAGE] = session.imageUrl
            p[Keys.CLIENT_TOKEN] = session.clientToken
            p[Keys.CLIENT_VERSION] = session.clientVersion
        }
    }

    override val extensionGrants: Flow<String?> = dataStore.data.map {
        it[stringPreferencesKey(com.aurora.music.extensions.ExtensionCodec.PREFERENCE_KEY)]
    }.distinctUntilChanged()

    override suspend fun setExtensionGrants(json: String) {
        com.aurora.music.extensions.ExtensionCodec.decode(json)
        dataStore.edit { it[stringPreferencesKey(com.aurora.music.extensions.ExtensionCodec.PREFERENCE_KEY)] = json }
    }

    override suspend fun updateToken(token: String) = dataStore.edit { it[Keys.TOKEN] = token }

    override suspend fun updateUserImage(url: String) = dataStore.edit { it[Keys.USER_IMAGE] = url }

    override suspend fun clearSession() {
        dataStore.edit { p ->
            p.remove(Keys.SERVER); p.remove(Keys.USERNAME); p.remove(Keys.SALT); p.remove(Keys.TOKEN)
            p.remove(Keys.SERVER_TYPE); p.remove(Keys.USER_ID); p.remove(Keys.USER_IMAGE)
            p.remove(Keys.CLIENT_TOKEN); p.remove(Keys.CLIENT_VERSION)
        }
    }

    override val savedSessions: Flow<List<Session>> = dataStore.data.map { it[Keys.SAVED_SESSIONS] }.distinctUntilChanged().map { parseSessions(it) }.flowOn(Dispatchers.Default)

    private fun parseSessions(json: String?): List<Session> = gson.parseList<Session>(json).filter { it.isValid }

    override suspend fun addSavedSession(session: Session) = dataStore.edit { p ->
        if (!session.isValid) return@edit
        val cur = parseSessions(p[Keys.SAVED_SESSIONS])
        // signing in takes the guest's place, in the merged library too
        val guest = if (session.isGuest) null else cur.firstOrNull { it.isGuest && it.type == session.type }
        val next = cur.filterNot { it.accountKey() == session.accountKey() || it == guest } + session
        p[Keys.SAVED_SESSIONS] = gson.toJson(next)
        val merged = p[Keys.MERGE_SOURCES].orEmpty()
        if (guest != null && guest.accountKey() in merged) p[Keys.MERGE_SOURCES] = merged - guest.accountKey() + session.accountKey()
    }

    override suspend fun removeSavedSession(session: Session) = dataStore.edit { p ->
        val cur = parseSessions(p[Keys.SAVED_SESSIONS])
        p[Keys.SAVED_SESSIONS] = gson.toJson(cur.filterNot { it.accountKey() == session.accountKey() })
    }

    override suspend fun setSpotifyClientId(v: String) = dataStore.edit { it[Keys.SPOTIFY_CLIENT_ID] = v.trim() }
    override suspend fun setSoundcloudClientId(v: String) { if (v.isNotBlank()) dataStore.edit { it[Keys.SOUNDCLOUD_CLIENT_ID] = v.trim() } }

    override val localProfile: Flow<LocalProfile> = dataStore.data.map { it[Keys.LOCAL_PROFILE] }.distinctUntilChanged().map {
        runCatching { LocalProfileCodec.decode(it) }.getOrDefault(LocalProfile())
    }.distinctUntilChanged()

    override suspend fun setLocalProfile(profile: LocalProfile) {
        val encoded = LocalProfileCodec.encode(profile)
        dataStore.edit { it[Keys.LOCAL_PROFILE] = encoded }
    }
}
