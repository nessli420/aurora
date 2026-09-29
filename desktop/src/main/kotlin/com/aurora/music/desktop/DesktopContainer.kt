package com.aurora.music.desktop

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import coil3.ImageLoader
import com.aurora.music.R
import com.aurora.music.data.ArtistInfoStore
import com.aurora.music.data.AutoEqController
import com.aurora.music.data.BackupManager
import com.aurora.music.data.DEFAULT_SOURCE_PRIORITY
import com.aurora.music.data.DEFAULT_SQUIG_BASE
import com.aurora.music.data.DEFAULT_SQUIG_TARGET
import com.aurora.music.data.DownloadManager
import com.aurora.music.data.JellyfinBackend
import com.aurora.music.data.LastfmScrobbler
import com.aurora.music.data.ListenBrainzScrobbler
import com.aurora.music.data.LocalBackend
import com.aurora.music.data.LocalStore
import com.aurora.music.data.LyricsRepository
import com.aurora.music.data.MERGE_NONE
import com.aurora.music.data.MediaBackend
import com.aurora.music.data.MergedBackend
import com.aurora.music.data.MusicRepository
import com.aurora.music.data.PlayHistoryStore
import com.aurora.music.data.PlaybackReportDispatcher
import com.aurora.music.data.PlaybackSourceIdentity
import com.aurora.music.data.PlexBackend
import com.aurora.music.data.QueueStore
import com.aurora.music.data.ReplayGainStore
import com.aurora.music.data.ReportingMediaBackend
import com.aurora.music.data.ServerType
import com.aurora.music.data.Session
import com.aurora.music.data.SettingsStore
import com.aurora.music.data.SmartPlaylist
import com.aurora.music.data.SmartPlaylistEngine
import com.aurora.music.data.SquigEqRepository
import com.aurora.music.data.SubsonicBackend
import com.aurora.music.data.accountKey
import com.aurora.music.data.artwork.ArtworkRepository
import com.aurora.music.data.artwork.ArtworkUrls
import com.aurora.music.data.remote.ArtistInfoClient
import com.aurora.music.data.remote.ClientInfo
import com.aurora.music.data.remote.JellyfinClient
import com.aurora.music.data.remote.PlexClient
import com.aurora.music.data.remote.SquigClient
import com.aurora.music.data.remote.SubsonicClient
import com.aurora.music.data.rules.RuleSource
import com.aurora.music.desktop.auth.AccountAuthenticator
import com.aurora.music.desktop.library.FolderLibrary
import com.aurora.music.desktop.platform.DesktopPaths
import com.aurora.music.desktop.platform.DesktopSettings
import com.aurora.music.desktop.platform.SkiaArtworkImages
import com.aurora.music.desktop.platform.desktopClientInfo
import com.aurora.music.desktop.platform.desktopFileUri
import com.aurora.music.desktop.platform.desktopImageLoader
import com.aurora.music.desktop.platform.openDesktopUri
import com.aurora.music.localization.AppStrings
import com.aurora.music.localization.appString
import com.aurora.music.model.Song
import com.aurora.music.playback.VisualizerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class DesktopContainer(
    val paths: DesktopPaths = DesktopPaths.default(),
    val clientInfo: ClientInfo = desktopClientInfo,
) : AutoCloseable {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val storageScope = CoroutineScope(SupervisorJob(scope.coroutineContext.job) + Dispatchers.IO)
    private val _sourceErrors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val sourceErrors: SharedFlow<String> = _sourceErrors.asSharedFlow()

    init { paths.createDirectories() }

    val settingsStore = SettingsStore(PreferenceDataStoreFactory.create(scope = storageScope) { paths.settingsFile }, paths.roaming, paths.staging)
    val desktopSettings = DesktopSettings(PreferenceDataStoreFactory.create(scope = storageScope) { paths.desktopSettingsFile })

    val playbackReportingAllowed: StateFlow<Boolean> = combine(settingsStore.playbackPrefs, settingsStore.privateSession) { prefs, private ->
        prefs.scrobble && !private
    }.stateIn(scope, SharingStarted.Eagerly, false)
    val playbackReports = PlaybackReportDispatcher(scope, allowed = { playbackReportingAllowed.value }) {
        _sourceErrors.tryEmit(appString(R.string.playback_history_sync_failed))
    }

    val playHistory = PlayHistoryStore(paths.roaming)
    val queueStore = QueueStore(paths.roaming)
    val replayGainStore = ReplayGainStore(paths.roaming)
    val artistInfoStore = ArtistInfoStore(paths.roaming)
    val artistInfoClient = ArtistInfoClient()
    private val localStore = LocalStore(paths.roaming)
    val backupManager = BackupManager(settingsStore, localStore, playHistory, paths.roaming, paths.cache)

    val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    val folderLibrary = FolderLibrary(paths.library, folders = { desktopSettings.musicFolders.first() }, fileUri = ::desktopFileUri,
        scope = scope, gainProvider = replayGainStore::gainsFor, separatorsProvider = { settingsStore.artistSeparators.first() })

    val authenticator = AccountAuthenticator(clientInfo)

    val autoEqController = AutoEqController(settingsStore, scope)

    @Volatile private var squigBaseValue: String = DEFAULT_SQUIG_BASE
    @Volatile private var squigTargetValue: String = DEFAULT_SQUIG_TARGET
    val squigEq = SquigEqRepository(SquigClient(), baseProvider = { squigBaseValue }, targetProvider = { squigTargetValue })

    @Volatile private var maxBitrate: Int = 0
    @Volatile private var downloadBitrate: Int = 0
    @Volatile private var preferLocalSources: Boolean = true
    @Volatile private var sourcePriorityValue: List<String> = DEFAULT_SOURCE_PRIORITY
    @Volatile private var unifiedLibraryValue: Boolean = false
    @Volatile private var mergeSourceKeys: Set<String> = emptySet()
    @Volatile private var lastSession: Session? = null
    @Volatile private var offlineToggle: Boolean = false
    @Volatile private var offlineFlag: Boolean = false
    @Volatile private var lrclibEnabled: Boolean = true
    @Volatile private var smartPlaylistsValue: List<SmartPlaylist> = emptyList()
    private val localMergeSession = Session(server = "On this device", username = "Local Library", salt = "", token = "local", type = ServerType.LOCAL)

    @Volatile
    var backend: MediaBackend? = null
        private set

    private fun currentServerId(): String = backend?.session?.server ?: ""

    val downloadManager: DownloadManager = DownloadManager(
        paths.downloads, ::desktopFileUri, ::openDesktopUri,
        streamUrlProvider = { id, bitrate, lossless -> backend?.streamUrl(id, bitrate, lossless) },
        downloadBitrateProvider = { downloadBitrate },
        currentServerIdProvider = { currentServerId() },
        playbackSourceProvider = { song -> backend?.playbackSourceIdentity(song) },
        playbackCollectionProvider = { kind, id, name -> repository.playbackCollectionIdentity(kind, id)?.copy(name = name) },
        downloadUrlResolverProvider = { backend?.let { source -> { id, bitrate, lossless -> source.downloadUrl(id, bitrate, lossless) } } },
    )

    val smartEngine = SmartPlaylistEngine(playHistory, downloadManager)

    val lastfm = LastfmScrobbler(settingsStore, scope)
    val listenBrainz = ListenBrainzScrobbler(settingsStore, scope)

    val visualizer = VisualizerController(scope)

    val lyricsRepository = LyricsRepository(backendProvider = { backend }, lrclibEnabledProvider = { lrclibEnabled },
        separatorsProvider = { settingsStore.artistSeparators.first() })

    val artworkRepository = ArtworkRepository(paths.artwork, ::openDesktopUri, SkiaArtworkImages,
        offline = { offlineToggle }, enabled = { settingsStore.artworkLookupEnabled.first() },
        separators = { settingsStore.artistSeparators.first() })

    private val imageLoaderDelegate = lazy { desktopImageLoader(paths.images, http, artworkRepository) }
    val imageLoader: ImageLoader by imageLoaderDelegate

    val repository: MusicRepository = MusicRepository(
        backendProvider = { backend },
        downloadManager = downloadManager,
        offlineProvider = { offlineFlag },
        currentServerIdProvider = { currentServerId() },
        smartPlaylistsProvider = { smartPlaylistsValue },
        smartEngine = smartEngine,
        coverUrl = ArtworkUrls::cover,
    )

    private val _sessionReady = MutableStateFlow<Boolean?>(null)
    val sessionReady: StateFlow<Boolean?> = _sessionReady.asStateFlow()

    private val _unsupportedAccount = MutableStateFlow<Session?>(null)
    val unsupportedAccount: StateFlow<Session?> = _unsupportedAccount.asStateFlow()

    // bumped only on a real account change not initial load or token refresh
    private val _accountEpoch = MutableStateFlow(0)
    val accountEpoch: StateFlow<Int> = _accountEpoch.asStateFlow()

    // reloads home/library without the playback-stopping semantics of an account change
    private val _libraryReload = MutableStateFlow(0)
    val libraryReload: StateFlow<Int> = _libraryReload.asStateFlow()
    @Volatile private var lastAccountKey: String? = null

    private val _offline = MutableStateFlow(false)
    val offline: StateFlow<Boolean> = _offline.asStateFlow()

    val noNetwork: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

    val isLocal: Boolean get() = backend?.session?.type == ServerType.LOCAL

    fun currentAccountKey(): String = backend?.session?.accountKey().orEmpty()

    // rewrites only streamUrl/metadata id stays the server's so server features keep working
    private fun localizeSong(song: Song): Song {
        if (!preferLocalSources) return song
        val alreadyLocal = song.streamUrl.startsWith("file:")
        for (tier in sourcePriorityValue) {
            when (tier) {
                "local" -> if (!alreadyLocal) {
                    folderLibrary.findMatch(song.artist, song.title, song.durationSec)?.let { return localizedFromFile(song, it) }
                }
                "downloaded" -> downloadManager.getByOriginalId(song.id, song.playbackSource?.providerId)
                    ?.let { return localizedFromDownload(song, it.toSong(downloadManager.fileUri)) }
                "stream" -> return song
            }
        }
        return song
    }

    private fun localizedFromFile(song: Song, local: Song): Song = song.copy(
        streamUrl = local.streamUrl,
        artworkUrl = song.artworkUrl.ifBlank { local.artworkUrl },
        replayGainTrack = local.replayGainTrack,
        replayGainAlbum = local.replayGainAlbum,
        suffix = local.suffix,
        bitrateKbps = local.bitrateKbps,
        sampleRateHz = local.sampleRateHz,
        bitDepth = local.bitDepth,
        path = local.path,
        playbackSource = PlaybackSourceIdentity.fromSession(localMergeSession, local.albumId, RuleSource.LOCAL_FILE),
    )

    private fun localizedFromDownload(song: Song, local: Song): Song = song.copy(
        streamUrl = local.streamUrl,
        artworkUrl = local.artworkUrl.ifBlank { song.artworkUrl },
        suffix = local.suffix,
        bitrateKbps = local.bitrateKbps,
        sampleRateHz = local.sampleRateHz,
        bitDepth = local.bitDepth,
        playbackSource = local.playbackSource,
    )

    private fun buildBackend(session: Session): MediaBackend? {
        val localize: (Song) -> Song = { song ->
            localizeSong(song.copy(playbackSource = song.playbackSource ?: PlaybackSourceIdentity.fromSession(session, song.albumId)))
        }
        return when (session.type) {
            ServerType.SUBSONIC -> SubsonicBackend(SubsonicClient(session), { maxBitrate }, localize)
            ServerType.JELLYFIN -> JellyfinBackend(JellyfinClient(session, clientInfo), { maxBitrate }, localize)
            ServerType.PLEX -> ReportingMediaBackend(PlexBackend(PlexClient(session, clientInfo), { maxBitrate }, localize)) { message ->
                if (lastSession?.accountKey() == session.accountKey() ||
                    (unifiedLibraryValue && lastSession?.type?.supportsMergedLibrary == true &&
                        (mergeSourceKeys.isEmpty() || session.accountKey() in mergeSourceKeys))) _sourceErrors.tryEmit(message)
            }
            ServerType.LOCAL -> LocalBackend(folderLibrary, localStore, session)
            ServerType.SPOTIFY, ServerType.YOUTUBE_MUSIC, ServerType.EXTENSION -> null
        }
    }

    private fun buildActiveBackend(session: Session, unified: Boolean, mergeKeys: Set<String>, saved: List<Session>): MediaBackend? {
        if (session.type !in AccountAuthenticator.SUPPORTED) return null
        if (!unified || !session.type.supportsMergedLibrary) return buildBackend(session)
        // empty = all eligible servers MERGE_NONE sentinel = local files only
        val serverSessions = if (mergeKeys == setOf(MERGE_NONE)) emptyList() else (saved + session)
            .filter { it.type in AccountAuthenticator.SUPPORTED && it.type.supportsMergedLibrary && it.type != ServerType.LOCAL }
            .filter { mergeKeys.isEmpty() || it.accountKey() in mergeKeys }
            .distinctBy { it.accountKey() }
        val sources = buildList {
            add(LocalBackend(folderLibrary, localStore, localMergeSession))
            serverSessions.forEach { buildBackend(it)?.let(::add) }
        }
        return MergedBackend(sources, session,
            priority = { if (preferLocalSources) sourcePriorityValue else listOf("stream", "local", "downloaded") },
            downloads = { downloadManager.downloads.value.values.filter { File(it.audioPath).isFile }.map { it.toSong(downloadManager.fileUri) } })
    }

    private suspend fun rebuildBackend() {
        val session = lastSession
        backend = session?.let {
            val saved = runCatching { settingsStore.savedSessions.first() }.getOrDefault(emptyList())
            buildActiveBackend(it, unifiedLibraryValue, mergeSourceKeys, saved)
        }
    }

    private fun publishSession(session: Session?) {
        _unsupportedAccount.value = session?.takeIf { it.type !in AccountAuthenticator.SUPPORTED }
        _sessionReady.value = session != null && backend != null
    }

    private fun recomputeOffline() {
        // local-files mode never needs the network so it's never offline
        offlineFlag = !isLocal && offlineToggle
        _offline.value = offlineFlag
    }

    init {
        scope.launch {
            settingsStore.artistSeparators.drop(1).collect { _libraryReload.value++ }
        }
        scope.launch {
            settingsStore.session.distinctUntilChanged().collect { session ->
                lastSession = session
                // keep a disk-restored session in the saved list so it shows up for switching
                session?.let { settingsStore.addSavedSession(it) }
                rebuildBackend()
                publishSession(session)
                // ignore the first load so startup doesn't count as an account change
                val key = session?.accountKey().orEmpty()
                if (lastAccountKey != null && lastAccountKey != key) _accountEpoch.value++
                lastAccountKey = key
                recomputeOffline()
            }
        }
        scope.launch {
            var first = true
            settingsStore.unifiedLibrary.distinctUntilChanged().collect {
                unifiedLibraryValue = it
                rebuildBackend()
                if (!first) _libraryReload.value++
                first = false
            }
        }
        scope.launch {
            settingsStore.savedSessions.distinctUntilChanged().drop(1).collect {
                if (unifiedLibraryValue && lastSession?.type?.supportsMergedLibrary == true) {
                    rebuildBackend()
                    _libraryReload.value++
                }
            }
        }
        scope.launch {
            var first = true
            settingsStore.mergeSources.distinctUntilChanged().collect {
                mergeSourceKeys = it
                rebuildBackend()
                if (!first) _libraryReload.value++
                first = false
            }
        }
        scope.launch {
            settingsStore.playbackPrefs.collect {
                maxBitrate = it.streamWifi
                downloadBitrate = it.downloadBitrate
            }
        }
        scope.launch {
            settingsStore.offlineMode.collect { offlineToggle = it; recomputeOffline() }
        }
        scope.launch {
            settingsStore.lrclibEnabled.collect { lrclibEnabled = it }
        }
        scope.launch {
            settingsStore.squigBaseUrl.collect { squigBaseValue = it }
        }
        scope.launch {
            settingsStore.squigTarget.collect { squigTargetValue = it }
        }
        scope.launch {
            settingsStore.smartPlaylists.collect { smartPlaylistsValue = it }
        }
        scope.launch {
            settingsStore.preferLocalSources.collect { on ->
                preferLocalSources = on
                // best-source matching needs the folder index so load it once when enabled
                if (on) runCatching { folderLibrary.ensureLoaded() }
            }
        }
        scope.launch {
            settingsStore.sourcePriority.collect { sourcePriorityValue = it }
        }
        scope.launch {
            desktopSettings.musicFolders.drop(1).collect { runCatching { folderLibrary.refresh() } }
        }
        scope.launch {
            folderLibrary.revision.drop(1).collect { if (isLocal || backend is MergedBackend) _libraryReload.value++ }
        }
        scope.launch {
            desktopSettings.languageTag.collect { if (it != AppStrings.languageTag.value) AppStrings.setLocale(it) }
        }
    }

    suspend fun applySession(session: Session) {
        lastSession = session
        settingsStore.saveSession(session)
        settingsStore.addSavedSession(session)
        rebuildBackend()
        publishSession(session)
    }

    suspend fun switchSession(session: Session) = applySession(session)

    suspend fun signOut() {
        lastSession = null
        backend = null
        publishSession(null)
        settingsStore.clearSession()
    }

    suspend fun forgetSavedSession(session: Session) {
        settingsStore.removeSavedSession(session)
        if (backend?.session?.accountKey() == session.accountKey() || lastSession?.accountKey() == session.accountKey()) signOut()
    }

    override fun close() {
        queueStore.flushNow()
        playHistory.flushNow()
        if (imageLoaderDelegate.isInitialized()) imageLoader.shutdown()
        scope.cancel()
    }
}
