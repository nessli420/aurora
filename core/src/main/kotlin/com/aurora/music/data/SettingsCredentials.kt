package com.aurora.music.data

import androidx.datastore.preferences.core.Preferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

fun Preferences.withoutCredentials(): Preferences = toMutablePreferences().apply {
    if (this[SettingsKeys.SERVER_TYPE] != ServerType.LOCAL.name) {
        remove(SettingsKeys.SALT)
        remove(SettingsKeys.TOKEN)
        remove(SettingsKeys.CLIENT_TOKEN)
    }
    this[SettingsKeys.SAVED_SESSIONS]?.let { json ->
        val sessions = runCatching { Gson().fromJson<List<Session>>(json, object : TypeToken<List<Session>>() {}.type) }.getOrNull().orEmpty()
        this[SettingsKeys.SAVED_SESSIONS] = Gson().toJson(sessions.filter { it.type == ServerType.LOCAL })
    }
    with(SettingsKeys) {
        listOf(ACOUSTID_KEY, LASTFM_SK, LASTFM_API_KEY, LASTFM_SECRET, LISTENBRAINZ_TOKEN, DISCORD_TOKEN).forEach { remove(it) }
    }
}.toPreferences()
