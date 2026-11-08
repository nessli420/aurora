package com.aurora.music.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.aurora.music.data.*
import com.aurora.music.data.SettingsKeys as Keys
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

interface CollectionSettings {
    val pins: Flow<List<Pin>>
    val smartPlaylists: Flow<List<SmartPlaylist>>
    suspend fun saveSmartPlaylist(sp: SmartPlaylist): Preferences
    suspend fun deleteSmartPlaylist(id: String): Preferences
    val radioFavorites: Flow<List<RadioStation>>
    suspend fun saveRadioStation(s: RadioStation): Preferences
    suspend fun deleteRadioStation(uuid: String): Preferences
    val podcastSubs: Flow<List<Podcast>>
    suspend fun savePodcast(p: Podcast): Preferences
    suspend fun deletePodcast(feedUrl: String): Preferences
    val likedPlaylists: Flow<Set<String>>
    val recentSearches: Flow<List<String>>
    suspend fun addRecentSearch(query: String)
    suspend fun removeRecentSearch(query: String): Preferences
    suspend fun clearRecentSearches(): Preferences
    val saveRecentSearches: Flow<Boolean>
    suspend fun setSaveRecentSearches(v: Boolean): Preferences
    suspend fun togglePin(pin: Pin): Preferences
    suspend fun setPlaylistLiked(id: String, liked: Boolean): Preferences
}

internal class CollectionSettingsStore(private val dataStore: DataStore<Preferences>) : CollectionSettings {
    private val gson = Gson()

    override val pins: Flow<List<Pin>> = dataStore.data.map { it[Keys.PINS] }.distinctUntilChanged().map { parsePins(it) }.flowOn(Dispatchers.Default)

    private fun parsePins(json: String?): List<Pin> = gson.parseList(json)

    override val smartPlaylists: Flow<List<SmartPlaylist>> = dataStore.data.map { it[Keys.SMART_PLAYLISTS] }.distinctUntilChanged().map { parseSmart(it) }.flowOn(Dispatchers.Default)

    private fun parseSmart(json: String?): List<SmartPlaylist> = gson.parseList(json)

    override suspend fun saveSmartPlaylist(sp: SmartPlaylist) = dataStore.edit { p ->
        val cur = parseSmart(p[Keys.SMART_PLAYLISTS])
        val next = if (cur.any { it.id == sp.id }) cur.map { if (it.id == sp.id) sp else it } else cur + sp
        p[Keys.SMART_PLAYLISTS] = gson.toJson(next)
    }

    override suspend fun deleteSmartPlaylist(id: String) = dataStore.edit { p ->
        p[Keys.SMART_PLAYLISTS] = gson.toJson(parseSmart(p[Keys.SMART_PLAYLISTS]).filterNot { it.id == id })
    }

    override val radioFavorites: Flow<List<RadioStation>> = dataStore.data.map { it[Keys.RADIO_FAVORITES] }.distinctUntilChanged().map { parseRadio(it) }.flowOn(Dispatchers.Default)

    private fun parseRadio(json: String?): List<RadioStation> = gson.parseList(json)

    override suspend fun saveRadioStation(s: RadioStation) = dataStore.edit { p ->
        val cur = parseRadio(p[Keys.RADIO_FAVORITES])
        val next = if (cur.any { it.uuid == s.uuid }) cur.map { if (it.uuid == s.uuid) s else it } else cur + s
        p[Keys.RADIO_FAVORITES] = gson.toJson(next)
    }

    override suspend fun deleteRadioStation(uuid: String) = dataStore.edit { p ->
        p[Keys.RADIO_FAVORITES] = gson.toJson(parseRadio(p[Keys.RADIO_FAVORITES]).filterNot { it.uuid == uuid })
    }

    override val podcastSubs: Flow<List<Podcast>> = dataStore.data.map { it[Keys.PODCAST_SUBS] }.distinctUntilChanged().map { parsePodcasts(it) }.flowOn(Dispatchers.Default)

    private fun parsePodcasts(json: String?): List<Podcast> = gson.parseList(json)

    override suspend fun savePodcast(p: Podcast) = dataStore.edit { prefs ->
        val cur = parsePodcasts(prefs[Keys.PODCAST_SUBS])
        val next = if (cur.any { it.feedUrl == p.feedUrl }) cur.map { if (it.feedUrl == p.feedUrl) p else it } else cur + p
        prefs[Keys.PODCAST_SUBS] = gson.toJson(next)
    }

    override suspend fun deletePodcast(feedUrl: String) = dataStore.edit { p ->
        p[Keys.PODCAST_SUBS] = gson.toJson(parsePodcasts(p[Keys.PODCAST_SUBS]).filterNot { it.feedUrl == feedUrl })
    }

    // persisted locally because subsonic has no playlist-star
    override val likedPlaylists: Flow<Set<String>> = dataStore.data.map { it[Keys.LIKED_PLAYLISTS] ?: emptySet() }.distinctUntilChanged().flowOn(Dispatchers.Default)

    override val recentSearches: Flow<List<String>> = dataStore.data.map { it[Keys.RECENT_SEARCHES] }.distinctUntilChanged().map { gson.parseList<String>(it) }.flowOn(Dispatchers.Default)

    override suspend fun addRecentSearch(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        dataStore.edit { p ->
            if (p[Keys.SAVE_SEARCHES] == false) return@edit
            val cur = gson.parseList<String>(p[Keys.RECENT_SEARCHES])
            val next = (listOf(q) + cur.filterNot { it.equals(q, ignoreCase = true) }).take(12)
            p[Keys.RECENT_SEARCHES] = gson.toJson(next)
        }
    }

    override suspend fun removeRecentSearch(query: String) = dataStore.edit { p ->
        p[Keys.RECENT_SEARCHES] = gson.toJson(gson.parseList<String>(p[Keys.RECENT_SEARCHES]).filterNot { it.equals(query, ignoreCase = true) })
    }

    override suspend fun clearRecentSearches() = dataStore.edit { it.remove(Keys.RECENT_SEARCHES) }

    override val saveRecentSearches: Flow<Boolean> = dataStore.data.map { it[Keys.SAVE_SEARCHES] ?: true }.distinctUntilChanged()
    override suspend fun setSaveRecentSearches(v: Boolean) = dataStore.edit {
        it[Keys.SAVE_SEARCHES] = v
        if (!v) it.remove(Keys.RECENT_SEARCHES)
    }

    override suspend fun togglePin(pin: Pin) = dataStore.edit { p ->
        val cur = parsePins(p[Keys.PINS])
        fun same(x: Pin) = x.id == pin.id && x.kind == pin.kind && x.serverId == pin.serverId
        val next = if (cur.any(::same)) cur.filterNot(::same) else cur + pin
        p[Keys.PINS] = gson.toJson(next)
    }

    override suspend fun setPlaylistLiked(id: String, liked: Boolean) = dataStore.edit { p ->
        val set = (p[Keys.LIKED_PLAYLISTS] ?: emptySet()).toMutableSet()
        if (liked) set.add(id) else set.remove(id)
        p[Keys.LIKED_PLAYLISTS] = set
    }
}
