package com.aurora.music.data.container

import com.aurora.music.data.JellyfinBackend
import com.aurora.music.data.LocalBackend
import com.aurora.music.data.LocalCatalog
import com.aurora.music.data.LocalStore
import com.aurora.music.data.MediaBackend
import com.aurora.music.data.PlaybackSourceIdentity
import com.aurora.music.data.PlexBackend
import com.aurora.music.data.ReportingMediaBackend
import com.aurora.music.data.ServerType
import com.aurora.music.data.Session
import com.aurora.music.data.SoundCloudBackend
import com.aurora.music.data.SoundCloudPendingLikes
import com.aurora.music.data.SourceLocalizer
import com.aurora.music.data.SpotifyWebBackend
import com.aurora.music.data.SubsonicBackend
import com.aurora.music.data.YouTubeMusicBackend
import com.aurora.music.data.remote.ClientInfo
import com.aurora.music.data.remote.JellyfinClient
import com.aurora.music.data.remote.PlexClient
import com.aurora.music.data.remote.SoundCloudBrowser
import com.aurora.music.data.remote.SoundCloudClient
import com.aurora.music.data.remote.SoundCloudWebSession
import com.aurora.music.data.remote.SpotifyGraphql
import com.aurora.music.data.remote.SubsonicClient
import com.aurora.music.data.remote.YouTubeMusicClient
import com.aurora.music.data.remote.YouTubeMusicWebSession
import com.aurora.music.data.settings.AccountSettings
import com.aurora.music.model.Song
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

class BackendFactory(
    private val clientInfo: ClientInfo,
    private val accounts: AccountSettings,
    private val localizer: SourceLocalizer,
    private val localLibrary: LocalCatalog,
    private val localStore: LocalStore,
    private val localSession: Session,
    private val soundcloudPendingLikes: SoundCloudPendingLikes,
    private val http: () -> OkHttpClient,
    private val maxBitrate: () -> Int,
    private val youtubeMusicLogin: (String) -> String,
    private val signatureTimestamp: () -> (String) -> Int?,
    private val spotifyWeb: (cookie: () -> String) -> SpotifyGraphql,
    private val spotifyLogin: (String) -> String,
    private val soundcloudLogin: (String) -> String,
    private val saveSoundcloudLogin: (token: String, login: String) -> Unit,
    private val soundcloudBrowser: () -> SoundCloudBrowser,
    private val reportError: (Session, String) -> Unit,
    private val eligible: (Session) -> Boolean = { true },
    private val platformBackend: (Session, (Song) -> Song) -> MediaBackend? = { _, _ -> null },
) {
    // one client per login: soundcloud rotates the refresh token, so two clients refreshing it would sign each other out
    private val soundcloudClients = ConcurrentHashMap<String, SoundCloudClient>()
    private fun soundcloudClient(session: Session) = soundcloudClients.getOrPut(session.token) {
        SoundCloudClient(
            session = { SoundCloudWebSession.decode(soundcloudLogin(session.token)) }.takeUnless { session.isGuest },
            onRefreshed = { saveSoundcloudLogin(session.token, it.encode()) }, http = http(), browser = if (session.isGuest) null else soundcloudBrowser(),
            savedClientId = { accounts.soundcloudClientId.first() }, onClientId = { accounts.setSoundcloudClientId(it) })
    }

    fun forgetSoundCloud(token: String) { soundcloudClients.remove(token) }

    // youtube music and soundcloud as you added them, signed out when you have not
    suspend fun catalogue(type: ServerType): MediaBackend? =
        build(accounts.savedSessions.first().firstOrNull { it.type == type && !it.isGuest } ?: Session.guest(type, "Guest"))

    fun build(session: Session): MediaBackend? {
        val localize: (Song) -> Song = { song ->
            localizer.localize(song.copy(playbackSource = song.playbackSource ?: PlaybackSourceIdentity.fromSession(session, song.albumId)))
        }
        return when (session.type) {
            ServerType.SUBSONIC -> SubsonicBackend(SubsonicClient(session), maxBitrate, localize)
            ServerType.JELLYFIN -> JellyfinBackend(JellyfinClient(session, clientInfo), maxBitrate, localize)
            ServerType.PLEX -> ReportingMediaBackend(PlexBackend(PlexClient(session, clientInfo), maxBitrate, localize)) { reportError(session, it) }
            ServerType.LOCAL -> LocalBackend(localLibrary, localStore, session)
            ServerType.SPOTIFY -> if (session.salt == SpotifyWebBackend.WEB_PLAYER)
                SpotifyWebBackend(session, spotifyWeb { spotifyLogin(session.token) }, localize) else platformBackend(session, localize)
            ServerType.YOUTUBE_MUSIC -> ReportingMediaBackend(YouTubeMusicBackend(session,
                YouTubeMusicClient(
                    session = { YouTubeMusicWebSession.decode(youtubeMusicLogin(session.token)) }.takeUnless { session.isGuest }, http = http()),
                signatureTimestamp(), localize)) { reportError(session, it) }
            ServerType.SOUNDCLOUD -> SoundCloudBackend(session, soundcloudClient(session), localize, soundcloudPendingLikes)
            ServerType.EXTENSION -> platformBackend(session, localize)
        }
    }

    fun buildActive(session: Session, unified: Boolean, mergeKeys: Set<String>, saved: List<Session>): MediaBackend? {
        if (!eligible(session)) return null
        if (!unified || !session.type.supportsMergedLibrary) return build(session)
        return localizer.mergedBackend(session, mergeKeys, saved, LocalBackend(localLibrary, localStore, localSession),
            eligible = eligible, build = ::build)
    }
}
