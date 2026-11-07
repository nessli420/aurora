package com.aurora.music.desktop

import coil3.ImageLoader
import com.aurora.music.R
import com.aurora.music.data.ArtistInfoStore
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
import com.aurora.music.data.MediaBackend
import com.aurora.music.data.MergedBackend
import com.aurora.music.data.MusicRepository
import com.aurora.music.data.PlayHistoryStore
import com.aurora.music.data.PlaybackReportDispatcher
import com.aurora.music.data.PlaybackSourceIdentity
import com.aurora.music.data.PlexBackend
import com.aurora.music.data.QueueStore
import com.aurora.music.data.ResumePoints
import com.aurora.music.data.ReplayGainStore
import com.aurora.music.data.ReportingMediaBackend
import com.aurora.music.data.ServerType
import com.aurora.music.data.Session
import com.aurora.music.data.SettingsStore
import com.aurora.music.data.SmartPlaylistEngine
import com.aurora.music.data.SourceLocalizer
import com.aurora.music.data.SquigEqRepository
import com.aurora.music.data.SubsonicBackend
import com.aurora.music.data.accountKey
import com.aurora.music.data.artwork.ArtworkRepository
import com.aurora.music.data.artwork.ArtworkUrls
import com.aurora.music.data.remote.ArtistInfoClient
import com.aurora.music.data.remote.AuroraHttp
import com.aurora.music.data.remote.ClientInfo
import com.aurora.music.data.remote.JellyfinClient
import com.aurora.music.data.remote.PlexClient
import com.aurora.music.data.remote.SquigClient
import com.aurora.music.data.remote.SubsonicClient
import com.aurora.music.desktop.auth.AccountAuthenticator
import com.aurora.music.desktop.library.FolderLibrary
import com.aurora.music.desktop.platform.DesktopPaths
import com.aurora.music.desktop.platform.DesktopSettings
import com.aurora.music.desktop.platform.SkiaArtworkImages
import com.aurora.music.desktop.platform.desktopClientInfo
import com.aurora.music.desktop.platform.desktopFileUri
import com.aurora.music.desktop.platform.desktopImageLoader
import com.aurora.music.desktop.platform.openDesktopUri
import com.aurora.music.desktop.platform.preferencesStore
import com.aurora.music.desktop.platform.protectedPreferencesStore
import com.aurora.music.localization.AppStrings
import com.aurora.music.localization.appString
import com.aurora.music.model.Song
import com.aurora.music.playback.VisualizerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.InputStream
import java.util.concurrent.TimeUnit

class DesktopContainer(
    val paths: DesktopPaths = DesktopPaths.default(),
    val clientInfo: ClientInfo = desktopClientInfo,
) : AutoCloseable {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val storageScope = CoroutineScope(SupervisorJob(scope.coroutineContext.job) + Dispatchers.IO)
    private val _sourceErrors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val sourceErrors: SharedFlow<String> = _sourceErrors.asSharedFlow()

    val http: OkHttpClient by lazy { AuroraHttp.base.newBuilder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build() }

    init {
        // warms tls off main; must stay below the delegate
        scope.launch(Dispatchers.IO) { http }
        paths.createDirectories()
        paths.moveLegacyQueue()
        com.aurora.music.util.NativeLibraries.loader = com.aurora.music.desktop.natives.NativeLoader::load
    }

    val settingsStore = SettingsStore(protectedPreferencesStore(paths.settingsFile, storageScope), paths.roaming, paths.staging)
    val desktopSettings = DesktopSettings(preferencesStore(paths.desktopSettingsFile, storageScope))

    val playbackReportingAllowed: StateFlow<Boolean> = combine(settingsStore.playbackPrefs, settingsStore.privateSession) { prefs, private ->
        prefs.scrobble && !private
    }.stateIn(scope, SharingStarted.Eagerly, false)
    val playbackReports = PlaybackReportDispatcher(scope, allowed = { playbackReportingAllowed.value }) {
        _sourceErrors.tryEmit(appString(R.string.playback_history_sync_failed))
    }

    val playHistory = PlayHistoryStore(paths.roaming)
    // machine-local, never in the roaming profile
    val listeningDetails by lazy { com.aurora.music.data.JsonLinesDetailedListeningStore(java.io.File(paths.local, "listening_details.jsonl"), com.aurora.music.desktop.platform.HostPlatform.name.lowercase(java.util.Locale.ROOT)) }
    val queueStore = QueueStore(paths.local)
    val resumePoints = ResumePoints(paths.local)
    val replayGainStore = ReplayGainStore(paths.roaming)
    val artistInfoStore = ArtistInfoStore(paths.roaming)
    val artistInfoClient by lazy { ArtistInfoClient(http) }
    val radioBrowser by lazy { com.aurora.music.data.remote.RadioBrowserClient(http) }
    val podcastClient by lazy { com.aurora.music.data.remote.PodcastClient(http) }
    val tagEditor = com.aurora.music.desktop.library.TagEditor()
    val musicBrainz by lazy { com.aurora.music.data.remote.MusicBrainzClient(http) }
    private val acoustIdKey = settingsStore.acoustIdKey.stateIn(scope, SharingStarted.Eagerly, "")
    val acoustId by lazy { com.aurora.music.data.remote.AcoustIdClient(apiKeyProvider = { acoustIdKey.value }, base = http) }
    private val localStore = LocalStore(paths.roaming)
    val listeningLevels = com.aurora.music.data.listening.ListeningLevelStore(java.io.File(paths.local, "listening_levels.json"), settingsStore.processingRoutes)
    val backupManager = BackupManager(settingsStore, localStore, playHistory, paths.roaming, paths.cache, listeningLevels)

    val folderLibrary = FolderLibrary(paths.library, folders = { desktopSettings.musicFolders.first() }, fileUri = ::desktopFileUri,
        scope = scope, gainProvider = replayGainStore::gainsFor, separatorsProvider = { settingsStore.artistSeparators.first() })

    val authenticator = AccountAuthenticator(clientInfo)

    // streaming services sign in through the embedded browser; their logins stay encrypted on this pc
    val youtubeMusicCredentials = com.aurora.music.desktop.platform.WebCredentials.youtubeMusic(paths.local)
    val spotifyCredentials = com.aurora.music.desktop.platform.WebCredentials.spotify(paths.local)
    val soundcloudCredentials = com.aurora.music.desktop.platform.WebCredentials.soundcloud(paths.local)
    val soundcloudWriter = com.aurora.music.desktop.web.SoundCloudWebWriter()
    private val soundcloudPendingLikes = com.aurora.music.data.SoundCloudPendingLikes(paths.local)
    val spotifyWeb = com.aurora.music.desktop.web.SpotifyWebBridge(java.io.File(paths.local, "web"))
    val trackChoices = com.aurora.music.data.TrackChoices(paths.local)
    val youtubeResolver by lazy { com.aurora.music.desktop.youtube.YoutubeResolver(http) { trackChoices.matchFor(it)?.videoId } }

    // one client per login: soundcloud rotates the refresh token, so two clients refreshing it would sign each other out
    init {
        com.aurora.music.desktop.web.Chromium.configure(java.io.File(paths.local, "chromium"))
    }

    private val soundcloudClients = java.util.concurrent.ConcurrentHashMap<String, com.aurora.music.data.remote.SoundCloudClient>()
    private fun soundcloudClient(session: Session) = soundcloudClients.getOrPut(session.token) {
        com.aurora.music.data.remote.SoundCloudClient(
            session = { com.aurora.music.data.remote.SoundCloudWebSession.decode(soundcloudCredentials.read(session.token)) }.takeUnless { session.isGuest },
            onRefreshed = { soundcloudCredentials.update(session.token, it.encode()) }, http = http, browser = if (session.isGuest) null else soundcloudWriter,
            savedClientId = { settingsStore.soundcloudClientId.first() }, onClientId = { settingsStore.setSoundcloudClientId(it) })
    }

    suspend fun verifySoundCloud(session: Session): Boolean = (buildBackend(session) as? com.aurora.music.data.SoundCloudBackend)?.verify() == true
    fun soundCloudWaiting(session: Session): Int = soundcloudPendingLikes.waiting(session.userId)

    // soundcloud previews and drm-only tracks play from a youtube match instead
    // youtube music and soundcloud as you added them, signed out when you have not
    private suspend fun catalogue(type: ServerType): MediaBackend? =
        buildBackend(settingsStore.savedSessions.first().firstOrNull { it.type == type && !it.isGuest } ?: Session.guest(type, "Guest"))

    val playlistImporter by lazy { com.aurora.music.data.PlaylistImporter(repository, com.aurora.music.data.PlaylistLinks(http), ::catalogue) }

    fun resolveSoundCloud(song: Song): String? {
        val id = song.streamUrl.substringAfterLast('/').substringBefore('?').takeIf { it.matches(Regex("[0-9]+")) } ?: return null
        val source = backend as? com.aurora.music.data.SoundCloudBackend ?: runBlocking { catalogue(ServerType.SOUNDCLOUD) } as? com.aurora.music.data.SoundCloudBackend
        runCatching { runBlocking { source?.playbackUrl(id) } }.getOrNull()?.let { return it }
        val query = java.net.URLEncoder.encode("${song.title} ${song.artist}".trim(), "UTF-8")
        return youtubeResolver.resolveSentinel("aurora-yt://sc$id?q=$query&dur=${song.durationSec}")
    }

    private val audioCacheDelegate = lazy { com.aurora.music.desktop.audio.AudioCache(java.io.File(paths.cache, "audio-streams"), http, settingsStore, offline = { offlineFlag }) }
    val audioCache by audioCacheDelegate

    val streamResolver = com.aurora.music.desktop.audio.StreamResolver { song ->
        localCopy(song)?.let { com.aurora.music.desktop.audio.StreamResolver.Default.resolve(it) } ?: audioCache.resolve(song) { resolveUpstream(song) }
    }

    // a song's audio for offline work like analysis or comparison: the cached file when there is one, never filling the cache
    fun sourceFor(song: Song): com.aurora.music.desktop.audio.ResolvedStream = audioCache.local(song) ?: resolveUpstream(song)

    private fun resolveUpstream(song: Song): com.aurora.music.desktop.audio.ResolvedStream {
        val url = song.streamUrl.trim()
        return when {
            url.startsWith("aurora-yt:") -> youtubeResolver.resolveSentinel(url)
                ?.let { com.aurora.music.desktop.audio.ResolvedStream(it, userAgent = com.aurora.music.desktop.youtube.YoutubeResolver.USER_AGENT) }
                ?: throw java.io.IOException("No YouTube stream was found for ${song.title}")
            url.startsWith("aurora-sc:") -> resolveSoundCloud(song)?.let { com.aurora.music.desktop.audio.ResolvedStream(it) }
                ?: throw java.io.IOException("SoundCloud could not play ${song.title}")
            else -> com.aurora.music.desktop.audio.StreamResolver.Default.resolve(song)
        }
    }

    private val squigBase = settingsStore.squigBaseUrl.stateIn(scope, SharingStarted.Eagerly, DEFAULT_SQUIG_BASE)
    private val squigTarget = settingsStore.squigTarget.stateIn(scope, SharingStarted.Eagerly, DEFAULT_SQUIG_TARGET)
    val squigEq by lazy { SquigEqRepository(SquigClient(http), baseProvider = { squigBase.value }, targetProvider = { squigTarget.value }) }

    @Volatile private var maxBitrate: Int = 0
    @Volatile private var downloadBitrate: Int = 0
    @Volatile private var preferLocalSources: Boolean = true
    private val sourcePriority = settingsStore.sourcePriority.stateIn(scope, SharingStarted.Eagerly, DEFAULT_SOURCE_PRIORITY)
    @Volatile private var unifiedLibraryValue: Boolean = false
    @Volatile private var mergeSourceKeys: Set<String> = emptySet()
    @Volatile private var lastSession: Session? = null
    @Volatile private var offlineToggle: Boolean = false
    @Volatile private var offlineFlag: Boolean = false
    private val lrclibEnabled = settingsStore.lrclibEnabled.stateIn(scope, SharingStarted.Eagerly, true)
    private val smartPlaylists = settingsStore.smartPlaylists.stateIn(scope, SharingStarted.Eagerly, emptyList())
    private val localMergeSession = AccountAuthenticator.LOCAL_SESSION

    @Volatile
    var backend: MediaBackend? = null
        private set

    private fun currentServerId(): String = backend?.session?.server ?: ""

    private fun openDownloadSource(uri: String): InputStream? = ArtworkUrls.decode(uri)?.let { request ->
        runCatching { runBlocking { artworkRepository.open(request) { it.readBytes() } } }.getOrNull()?.inputStream()
    } ?: openDesktopUri(uri)

    val downloadManager: DownloadManager = DownloadManager(
        paths.downloads, ::desktopFileUri, ::openDownloadSource,
        streamUrlProvider = { id, bitrate, lossless -> backend?.streamUrl(id, bitrate, lossless) },
        downloadBitrateProvider = { downloadBitrate },
        currentServerIdProvider = { currentServerId() },
        resolveSentinel = { youtubeResolver.resolveSentinel(it) },
        playbackSourceProvider = { song -> backend?.playbackSourceIdentity(song) },
        playbackCollectionProvider = { kind, id, name -> repository.playbackCollectionIdentity(kind, id)?.copy(name = name) },
        downloadUrlResolverProvider = { backend?.let { source -> { id, bitrate, lossless -> source.downloadUrl(id, bitrate, lossless) } } },
        copyCached = { uri, file, progress -> audioCache.copyTo(uri, file, progress) },
    )

    private val catalogueMatch = settingsStore.catalogueMatch.stateIn(scope, SharingStarted.Eagerly, com.aurora.music.data.CatalogueMatchPrefs())

    private val sourceLocalizer = SourceLocalizer(localMergeSession, downloadManager,
        preferLocal = { preferLocalSources }, priority = { sourcePriority.value },
        isLocalUrl = { it.startsWith("file:") }, findLocal = folderLibrary::findMatch,
        catalogueMatch = { catalogueMatch.value })

    val smartEngine = SmartPlaylistEngine(playHistory, downloadManager)

    val lastfm = LastfmScrobbler(settingsStore, scope)
    val listenBrainz = ListenBrainzScrobbler(settingsStore, scope)

    val visualizer = VisualizerController(scope)
    val placesStore = com.aurora.music.desktop.platform.PlacesStore(paths.local)
    val sonicStore = com.aurora.music.data.SonicStore(paths.local)
    val autoEqController = com.aurora.music.data.AutoEqController(settingsStore, scope)
    val mixAnalyzer by lazy {
        com.aurora.music.mix.MixAnalyzer(paths.local, ::sourceFor) { account, id, vector -> sonicStore.put(id, vector, account) }
    }
    val mixController = com.aurora.music.mix.MixController()
    val animatedCovers = com.aurora.music.data.AnimatedCovers(settingsStore, scope)
    val youTubeDownloader by lazy {
        com.aurora.music.desktop.youtube.YouTubeDownloader(youtubeResolver, { settingsStore.youTubeDownloadPrefs.first() }, http,
            java.io.File(paths.cache, "youtube-downloads"), { youTubeDownloadFolder }, onSaved = { saved ->
            val folder = saved.parentFile.canonicalFile
            val folders = desktopSettings.musicFolders.first().map { java.io.File(it).canonicalFile }
            if (folders.none { folder.startsWith(it) }) desktopSettings.addMusicFolder(folder.path) else folderLibrary.refresh()
        })
    }
    val storage by lazy { com.aurora.music.data.StorageInventory(this) }
    val youTubeDownloadFolder: java.io.File get() = java.io.File(System.getProperty("user.home"), "Music${java.io.File.separator}Aurora")
    val appUpdater by lazy { com.aurora.music.desktop.updates.DesktopUpdater(http, java.io.File(paths.cache, "updates"),
        com.aurora.music.desktop.platform.BuildInfo.VERSION_NAME, com.aurora.music.desktop.platform.HostPlatform.isWindows) }
    val companionVideo by lazy { com.aurora.music.desktop.video.CompanionVideo(youtubeResolver, { repository.canvasUrl(it) }, ::sourceFor,
        http, java.io.File(paths.cache, "canvas")) }
    val mixStore = com.aurora.music.mix.MixStore(paths.local)
    val stemSeparator by lazy { com.aurora.music.mix.StemSeparator(paths.local, paths.cache, ::sourceFor) }
    val mixService by lazy { com.aurora.music.desktop.audio.mix.MixService(mixController, settingsStore, { song -> if (song.streamUrl.startsWith("file:")) com.aurora.music.desktop.audio.ResolvedStream(song.streamUrl) else streamResolver.resolve(song) }, scope) }
    val sonicEngine by lazy {
        com.aurora.music.data.SonicEngine(sonicStore, { repository.allLibrarySongs(cap = Int.MAX_VALUE) },
            { settingsStore.session.first()?.accountKey().orEmpty() }, mixAnalyzer, libraryKey = { offline.value to libraryReload.value })
    }
    val aiDjModels by lazy { com.aurora.music.ai.DjModelStore(java.io.File(paths.local, "ai-dj-model"), http) }
    private val aiDjLlm by lazy { com.aurora.music.ai.LocalLlm({ aiDjModels.model() }, (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 8)) }
    val aiDjMemory by lazy { com.aurora.music.ai.DjMemory(java.io.File(paths.local, "ai-dj-memory.json")) }
    private val aiDjDelegate = lazy {
        com.aurora.music.ai.DjEngine(repository, playHistory, aiDjLlm, { aiDjModels.state.value == com.aurora.music.ai.DjModelState.Ready },
            { sonicStore.snapshot() }, { seed, count, pool -> if (sonicStore.get(seed.id) != null) sonicEngine.buildRadio(seed, count, pool) else emptyList() }, aiDjMemory)
    }
    val aiDj by aiDjDelegate
    fun downloadAiDjModel() = aiDjModels.download()
    suspend fun deleteAiDjModel() = aiDjModels.delete { aiDjLlm.release() }
    val aiDjVoiceModels by lazy { com.aurora.music.ai.DjModelStore(java.io.File(paths.local, "ai-dj-voice"), http, com.aurora.music.ai.DjModelStore.WHISPER) }
    private val aiDjWhisper by lazy { com.aurora.music.ai.LocalWhisper({ aiDjVoiceModels.model() }, (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 8)) }
    val aiDjSpeakerModels by lazy { com.aurora.music.ai.DjModelStore(java.io.File(paths.local, "ai-dj-speaker"), http, com.aurora.music.ai.DjModelStore.SPEAKER) }
    private val aiDjSpeaker by lazy { com.aurora.music.ai.DjSpeaker({ aiDjSpeakerModels.model() }, (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 8)) }
    val aiDjVoice by lazy { com.aurora.music.desktop.platform.DesktopDjVoice(paths.cache, aiDjSpeaker) { settingsStore.aiDjSpoken.first() } }
    fun downloadAiDjSpeakerModel() = aiDjSpeakerModels.download()
    suspend fun deleteAiDjSpeakerModel() = aiDjSpeakerModels.delete { aiDjSpeaker.release() }
    val aiDjEars by lazy {
        com.aurora.music.ai.WhisperEars(com.aurora.music.desktop.platform.DesktopDjMic(), aiDjWhisper) { com.aurora.music.localization.AppStrings.locale.language }
    }
    fun downloadAiDjVoiceModel() = aiDjVoiceModels.download()
    suspend fun deleteAiDjVoiceModel() = aiDjVoiceModels.delete { aiDjWhisper.release() }
    val mapTiles by lazy { com.aurora.music.ui.components.MapTiles(java.io.File(paths.cache, "map-tiles"), http) }
    // the local discord account, null while the discord app isn't running
    val discordUser = MutableStateFlow<String?>(null)
    private val discordDelegate = lazy { com.aurora.music.data.DiscordRpc(settingsStore, scope, discordUser) { url -> com.aurora.music.desktop.player.artworkBytes(url, http, artworkRepository) } }
    val discord by discordDelegate

    val lyricsRepository by lazy {
        LyricsRepository(backendProvider = { backend }, lrclibEnabledProvider = { lrclibEnabled.value },
            separatorsProvider = { settingsStore.artistSeparators.first() },
            orderProvider = { settingsStore.lyricsSources.first() },
            spotifyAllowed = { type -> settingsStore.mergeFeatures.first().lyricsFor(type) },
            http = http.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build())
    }

    val artworkRepository by lazy {
        val artworkHttp = http.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
        ArtworkRepository(paths.artwork, ::openDesktopUri, SkiaArtworkImages,
            offline = { offlineToggle }, enabled = { settingsStore.artworkLookupEnabled.first() },
            separators = { settingsStore.artistSeparators.first() },
            client = com.aurora.music.data.artwork.CoverArtClient(artworkHttp), http = artworkHttp)
    }
    // keeps the startup trim, the storage page reads its total once
    init { scope.launch(Dispatchers.IO) { artworkRepository } }

    private val imageLoaderDelegate = lazy { desktopImageLoader(paths.images, http, artworkRepository) }
    val imageLoader: ImageLoader by imageLoaderDelegate

    val repository: MusicRepository = MusicRepository(
        backendProvider = { backend },
        downloadManager = downloadManager,
        offlineProvider = { offlineFlag },
        currentServerIdProvider = { currentServerId() },
        smartPlaylistsProvider = { smartPlaylists.value },
        smartEngine = smartEngine,
        coverUrl = ArtworkUrls::cover,
        cachedSongsProvider = { audioCache.songs.value },
        likeSourcesProvider = { likeAccounts.of(backend, settingsStore.savedSessions.first()) },
        trackChoices = trackChoices,
    )

    private val likeAccounts = com.aurora.music.data.LikeAccounts { if (AccountAuthenticator.supports(it)) buildBackend(it) else null }

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

    // the downloaded or on-device copy of a song, when it should play instead of the stream
    fun localCopy(song: Song): Song? = sourceLocalizer.localize(song).takeIf { it.streamUrl != song.streamUrl }

    private fun buildBackend(session: Session): MediaBackend? {
        val localize: (Song) -> Song = { song ->
            sourceLocalizer.localize(song.copy(playbackSource = song.playbackSource ?: PlaybackSourceIdentity.fromSession(session, song.albumId)))
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
            ServerType.SPOTIFY -> if (session.salt == com.aurora.music.data.SpotifyWebBackend.WEB_PLAYER)
                com.aurora.music.data.SpotifyWebBackend(session, spotifyWeb.account { spotifyCredentials.read(session.token) }, localize) else null
            ServerType.YOUTUBE_MUSIC -> ReportingMediaBackend(com.aurora.music.data.YouTubeMusicBackend(session,
                com.aurora.music.data.remote.YouTubeMusicClient(
                    session = { com.aurora.music.data.remote.YouTubeMusicWebSession.decode(youtubeMusicCredentials.read(session.token)) }.takeUnless { session.isGuest }, http = http),
                youtubeResolver::signatureTimestamp, localize)) { message ->
                if (lastSession?.accountKey() == session.accountKey() ||
                    (unifiedLibraryValue && lastSession?.type?.supportsMergedLibrary == true &&
                        (mergeSourceKeys.isEmpty() || session.accountKey() in mergeSourceKeys))) _sourceErrors.tryEmit(message)
            }
            ServerType.SOUNDCLOUD -> com.aurora.music.data.SoundCloudBackend(session, soundcloudClient(session), localize, soundcloudPendingLikes)
            ServerType.EXTENSION -> null
        }
    }

    private fun buildActiveBackend(session: Session, unified: Boolean, mergeKeys: Set<String>, saved: List<Session>): MediaBackend? {
        if (!AccountAuthenticator.supports(session)) return null
        if (!unified || !session.type.supportsMergedLibrary) return buildBackend(session)
        return sourceLocalizer.mergedBackend(session, mergeKeys, saved, LocalBackend(folderLibrary, localStore, localMergeSession),
            eligible = AccountAuthenticator::supports, build = ::buildBackend)
    }

    private suspend fun rebuildBackend() {
        val session = lastSession
        backend = session?.let {
            val saved = runCatching { settingsStore.savedSessions.first() }.getOrDefault(emptyList())
            buildActiveBackend(it, unifiedLibraryValue, mergeSourceKeys, saved)
        }
    }

    private fun publishSession(session: Session?) {
        _unsupportedAccount.value = session?.takeIf { !AccountAuthenticator.supports(it) }
        _sessionReady.value = session != null && backend != null
    }

    private fun forgetAiDjLibrary() { if (aiDjDelegate.isInitialized()) aiDj.forgetLibrary() }

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
            var previous: Triple<Session?, Boolean, Set<String>>? = null
            // one collector, so launch builds once with the merge settings already known
            combine(settingsStore.session.distinctUntilChanged(), settingsStore.unifiedLibrary.distinctUntilChanged(),
                settingsStore.mergeSources.distinctUntilChanged()) { session, unified, merge -> Triple(session, unified, merge) }
                .distinctUntilChanged().collect { current ->
                    val (session, unified, merge) = current
                    val last = previous
                    previous = current
                    val sessionChanged = last == null || last.first != session
                    unifiedLibraryValue = unified
                    mergeSourceKeys = merge
                    if (sessionChanged) {
                        lastSession = session
                        // keep a disk-restored session in the saved list so it shows up for switching
                        session?.let { rememberSession(it) }
                    }
                    rebuildBackend()
                    if (sessionChanged) {
                        publishSession(session)
                        // ignore the first load so startup doesn't count as an account change
                        val key = session?.accountKey().orEmpty()
                        if (lastAccountKey != null && lastAccountKey != key) _accountEpoch.value++
                        lastAccountKey = key
                        recomputeOffline()
                        sonicEngine.cancel()
                        withContext(Dispatchers.IO) { sonicStore.selectAccount(key) }
                        if (session != null && settingsStore.sonicAutoAnalyze.first()) sonicEngine.scan()
                    }
                    if (last != null && (last.second != unified || last.third != merge)) { _libraryReload.value++; forgetAiDjLibrary() }
                }
        }
        scope.launch {
            settingsStore.savedSessions.distinctUntilChanged().drop(1).collect {
                if (unifiedLibraryValue && lastSession?.type?.supportsMergedLibrary == true) {
                    rebuildBackend()
                    _libraryReload.value++
                    forgetAiDjLibrary()
                }
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
            settingsStore.preferLocalSources.collect { on ->
                preferLocalSources = on
                // best-source matching needs the folder index so load it once when enabled
                if (on) runCatching { folderLibrary.ensureLoaded() }
            }
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
        rememberSession(session)
        rebuildBackend()
        publishSession(session)
    }

    suspend fun switchSession(session: Session) = applySession(session)

    private suspend fun rememberSession(session: Session) {
        val saved = settingsStore.savedSessions.first()
        if (session.type == ServerType.LOCAL) saved
            .filter { it.type == ServerType.LOCAL && it.accountKey() != session.accountKey() }
            .forEach { settingsStore.removeSavedSession(it) }
        // launch skips the write when it is already the newest
        if (saved.lastOrNull() != session) settingsStore.addSavedSession(session)
    }

    suspend fun signOut() {
        lastSession = null
        backend = null
        publishSession(null)
        settingsStore.clearSession()
    }

    suspend fun forgetSavedSession(session: Session) {
        settingsStore.removeSavedSession(session)
        when (session.type) {
            ServerType.YOUTUBE_MUSIC -> if (!session.isGuest) youtubeMusicCredentials.remove(session.token)
            ServerType.SOUNDCLOUD -> { soundcloudClients.remove(session.token); if (!session.isGuest) soundcloudCredentials.remove(session.token) }
            ServerType.SPOTIFY -> if (session.salt == com.aurora.music.data.SpotifyWebBackend.WEB_PLAYER) {
                spotifyCredentials.remove(session.token)
                spotifyWeb.reset()
                runCatching { com.aurora.music.desktop.web.SpotifyWebPlayer.clearCookies() }
            }
            else -> Unit
        }
        if (backend?.session?.accountKey() == session.accountKey() || lastSession?.accountKey() == session.accountKey()) signOut()
    }

    override fun close() {
        folderLibrary.close()
        queueStore.flushNow()
        resumePoints.flushNow()
        playHistory.flushNow()
        if (imageLoaderDelegate.isInitialized()) imageLoader.shutdown()
        if (audioCacheDelegate.isInitialized()) audioCache.close()
        if (discordDelegate.isInitialized()) discord.close()
        runCatching { mixService.close() }
        scope.cancel()
    }
}
