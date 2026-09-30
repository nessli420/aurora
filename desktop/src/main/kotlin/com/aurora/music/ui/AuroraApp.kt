package com.aurora.music.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.backhandler.LocalBackGestureDispatcher
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.savedstate.read
import com.aurora.music.R
import com.aurora.music.data.DownloadState
import com.aurora.music.data.GesturePrefs
import com.aurora.music.data.M3u
import com.aurora.music.data.Pin
import com.aurora.music.data.RecapPeriod
import com.aurora.music.data.RecapWindow
import com.aurora.music.data.ServerType
import com.aurora.music.data.TabletSetting
import com.aurora.music.data.ThemeStyle
import com.aurora.music.desktop.ui.FilePickers
import com.aurora.music.desktop.ui.LocalDesktopContainer
import com.aurora.music.desktop.ui.LocalPlayer
import com.aurora.music.desktop.ui.BackDispatcher
import com.aurora.music.desktop.ui.Shortcut
import com.aurora.music.localization.AppStrings
import com.aurora.music.localization.appPlural
import com.aurora.music.localization.appString
import com.aurora.music.localization.localizedMediaType
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import com.aurora.music.model.accent
import com.aurora.music.navigation.NavLayout
import com.aurora.music.navigation.NavMenu
import com.aurora.music.navigation.NavMenuItem
import com.aurora.music.navigation.Routes
import com.aurora.music.ui.components.AmbientBackground
import com.aurora.music.ui.components.LottieLoader
import com.aurora.music.ui.components.PlaybackDock
import com.aurora.music.ui.components.TabletNavigationRail
import com.aurora.music.ui.components.TabletSidebar
import com.aurora.music.ui.layout.LocalPageGutter
import com.aurora.music.ui.layout.LocalWindowLayout
import com.aurora.music.ui.layout.PageMetrics
import com.aurora.music.ui.layout.TabletMetrics
import com.aurora.music.ui.layout.shell
import com.aurora.music.ui.screens.auth.SignInScreen
import com.aurora.music.ui.screens.detail.DetailScreen
import com.aurora.music.ui.screens.home.HomeScreen
import com.aurora.music.ui.screens.library.DuplicatesScreen
import com.aurora.music.ui.screens.library.FolderScreen
import com.aurora.music.ui.screens.library.LibraryScreen
import com.aurora.music.ui.screens.library.SmartPlaylistEditScreen
import com.aurora.music.ui.screens.player.LyricsPane
import com.aurora.music.ui.screens.player.NowPlayingSidePanel
import com.aurora.music.ui.screens.player.OutputDeviceSheet
import com.aurora.music.ui.screens.player.PlayerPane
import com.aurora.music.ui.screens.player.PlayerScreen
import com.aurora.music.ui.screens.player.QueueActions
import com.aurora.music.ui.screens.player.QueueContent
import com.aurora.music.ui.screens.player.SleepTimerSheet
import com.aurora.music.ui.screens.player.SpeedPitchSheet
import com.aurora.music.ui.screens.profile.LocalProfileDialog
import com.aurora.music.ui.screens.profile.ProfileScreen
import com.aurora.music.ui.screens.profile.rememberProfileAppearance
import com.aurora.music.ui.screens.search.SearchScreen
import com.aurora.music.ui.screens.settings.AboutSettingsScreen
import com.aurora.music.ui.screens.settings.AccountsScreen
import com.aurora.music.ui.screens.settings.AdvancedAudioSettingsScreen
import com.aurora.music.ui.screens.settings.AppearanceScreen
import com.aurora.music.ui.screens.settings.ArtistInfoIntegrationScreen
import com.aurora.music.ui.screens.settings.AudioOutputSettingsScreen
import com.aurora.music.ui.screens.settings.BackupScreen
import com.aurora.music.ui.screens.settings.ConvolutionLibraryScreen
import com.aurora.music.ui.screens.settings.EqualizerScreen
import com.aurora.music.ui.screens.settings.IntegrationsSettingsScreen
import com.aurora.music.ui.screens.settings.LanguageSettingsScreen
import com.aurora.music.ui.screens.settings.LastfmIntegrationScreen
import com.aurora.music.ui.screens.settings.ListenBrainzIntegrationScreen
import com.aurora.music.ui.screens.settings.LocalSelectedSettingsRoute
import com.aurora.music.ui.screens.settings.LocalSettingsPaneRoots
import com.aurora.music.ui.screens.settings.LoudnessSettingsScreen
import com.aurora.music.ui.screens.settings.LyricsIntegrationScreen
import com.aurora.music.ui.screens.settings.NavigationMenuScreen
import com.aurora.music.ui.screens.settings.PlaybackSettingsScreen
import com.aurora.music.ui.screens.settings.ProcessingPresetsScreen
import com.aurora.music.ui.screens.settings.ProcessingRackScreen
import com.aurora.music.ui.screens.settings.SettingsScreen
import com.aurora.music.ui.screens.settings.SignalPathScreen
import com.aurora.music.ui.screens.settings.SourcesSettingsScreen
import com.aurora.music.ui.screens.settings.StorageSettingsScreen
import com.aurora.music.ui.screens.settings.VisualizerSettingsScreen
import com.aurora.music.ui.screens.stats.ListeningHistoryScreen
import com.aurora.music.ui.screens.stats.ListeningStatsScreen
import com.aurora.music.ui.screens.stats.RecapInboxScreen
import com.aurora.music.ui.screens.visualizer.VisualizerScreen
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.auroraPanel
import com.aurora.music.ui.theme.rememberPlayerColorScheme
import com.aurora.music.viewmodel.AuthViewModel
import com.aurora.music.viewmodel.DetailViewModel
import com.aurora.music.viewmodel.DuplicatesViewModel
import com.aurora.music.viewmodel.FolderViewModel
import com.aurora.music.viewmodel.HomeViewModel
import com.aurora.music.viewmodel.LibraryViewModel
import com.aurora.music.viewmodel.SearchViewModel
import com.aurora.music.viewmodel.SmartPlaylistViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private val unportedRoutes = setOf(
    Routes.RADIO, Routes.PODCASTS, Routes.SETTINGS_TUNING, Routes.SETTINGS_COMPARISON, Routes.SETTINGS_PRESET_RULES, Routes.SETTINGS_LISTENING,
)
private val splitKinds = listOf("album", "artist", "playlist", "liked", "smart")
private val mouseGestures = GesturePrefs(swipeArtwork = false)
private const val LIKES_REFRESH_INTERVAL_NS = 30_000_000_000L

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AuroraApp(
    navController: NavHostController = rememberNavController(),
    shortcuts: Flow<Shortcut> = emptyFlow(),
    fullscreen: Boolean = false,
    onFullscreenChange: (Boolean) -> Unit = {},
) {
    val owner = LocalViewModelStoreOwner.current ?: rememberRootOwner()
    val backDispatcher = remember { BackDispatcher() }
    val language by AppStrings.languageTag.collectAsState()
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, LocalBackGestureDispatcher provides backDispatcher) {
        key(language) { Shell(navController, owner, backDispatcher::back, shortcuts, fullscreen, onFullscreenChange) }
    }
}

@Composable
private fun rememberRootOwner(): ViewModelStoreOwner {
    val owner = remember { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    return owner
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Shell(
    navController: NavHostController,
    rootOwner: ViewModelStoreOwner,
    back: () -> Boolean,
    shortcuts: Flow<Shortcut>,
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
) {
    val container = LocalDesktopContainer.current
    val player = LocalPlayer.current
    val store = container.settingsStore
    val repository = container.repository
    val authVM = viewModel(viewModelStoreOwner = rootOwner) { AuthViewModel(container) }

    val playerState by player.state.collectAsStateWithLifecycle()
    val volume by player.volume.collectAsStateWithLifecycle()
    val authState by authVM.state.collectAsStateWithLifecycle()
    val musicFolders by authVM.musicFolders.collectAsStateWithLifecycle()
    val sessionReady by container.sessionReady.collectAsStateWithLifecycle()
    val session by store.session.collectAsStateWithLifecycle(initialValue = null)
    val savedSessions by store.savedSessions.collectAsStateWithLifecycle(initialValue = emptyList())
    val downloadsMap by container.downloadManager.downloads.collectAsStateWithLifecycle()
    val localMode = session?.type == ServerType.LOCAL
    val profileAppearance = rememberProfileAppearance(session)
    val currentServer = session?.server ?: ""
    val allPins by store.pins.collectAsStateWithLifecycle(initialValue = emptyList())
    val pins = allPins.filter { it.serverId == currentServer }
    val offlineMode by container.offline.collectAsStateWithLifecycle()
    val accountEpoch by container.accountEpoch.collectAsStateWithLifecycle()
    val libraryReload by container.libraryReload.collectAsStateWithLifecycle()
    val downloadedIds = remember(downloadsMap, session, sessionReady, offlineMode, accountEpoch, libraryReload) {
        repository.downloadedIds()
    }
    val downloadStates by container.downloadManager.states.collectAsStateWithLifecycle()
    val simpleMode by store.simpleMode.collectAsStateWithLifecycle(initialValue = false)
    val unsupportedAccount by container.unsupportedAccount.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(container, player) {
        merge(container.sourceErrors, player.messages).collect { message ->
            if (snackbarHostState.currentSnackbarData == null) snackbarHostState.showSnackbar(message)
        }
    }
    val scope = rememberCoroutineScope()
    fun confirm(message: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }
    LaunchedEffect(unsupportedAccount) {
        unsupportedAccount?.let { confirm("${it.typeLabel.localizedMediaType()}: ${appString(R.string.text_not_supported_on_this_device_a07536)}") }
    }

    val onDownload: (Song) -> Unit = {
        val already = repository.isDownloaded(it.id)
        val started = container.downloadManager.downloadSong(it)
        confirm(when {
            already -> appString(R.string.text_already_downloaded_8acc8a)
            !started -> appString(R.string.download_start_failed)
            else -> appString(R.string.text_downloading_7f12a1, it.title)
        })
    }
    val onRemoveDownload: (String) -> Unit = {
        if (repository.removeDownload(it)) confirm(appString(R.string.text_removed_download_8ad8f9))
    }

    // the window resumes on every focus so likes starred elsewhere refresh at most once per interval
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        var refreshedAt: Long? = null
        val observer = LifecycleEventObserver { _, event ->
            val now = System.nanoTime()
            if (event == Lifecycle.Event.ON_RESUME && refreshedAt?.let { now - it < LIKES_REFRESH_INTERVAL_NS } != true) {
                refreshedAt = now
                player.refreshLikes()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val activeDownloads = downloadStates.values.count { it is DownloadState.Queued || it is DownloadState.Downloading }
    val downloadProgress = downloadStates.values
        .mapNotNull { (it as? DownloadState.Downloading)?.progress }
        .let { if (it.isEmpty()) 0f else it.sum() / activeDownloads.coerceAtLeast(1) }
    var hadActiveDownloads by remember { mutableStateOf(false) }
    LaunchedEffect(activeDownloads) {
        if (activeDownloads > 0) {
            hadActiveDownloads = true
        } else if (hadActiveDownloads) {
            hadActiveDownloads = false
            val failed = downloadStates.values.count { it is DownloadState.Failed }
            snackbarHostState.showSnackbar(
                if (failed > 0) appString(R.string.text_download_finished_failed_21e6b1, failed) else appString(R.string.text_download_complete_95efb3),
                duration = SnackbarDuration.Short,
            )
        }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var drawerNavigationPending by remember { mutableStateOf(false) }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showChrome = currentRoute != null && currentRoute != Routes.SIGN_IN
    val windowLayout = LocalWindowLayout.current
    val rail = windowLayout.useNavigationRail && showChrome
    var sidePaneName by rememberSaveable { mutableStateOf<String?>(null) }
    val sidePane = sidePaneName?.let { runCatching { PlayerPane.valueOf(it) }.getOrNull() }
    val shell = windowLayout.shell(panelRequested = rail && sidePane != null && playerState.hasTrack)
    val uiPrefs = LocalUiPrefs.current
    val navLayout = remember(uiPrefs.navLayout, simpleMode) { NavMenu.parse(uiPrefs.navLayout).visible(simpleMode).ported() }
    val navGap = if (rail) (TabletMetrics.NavGap + (uiPrefs.tabletNavGap - TabletSetting.NAV_GAP.default).dp).coerceAtLeast(0.dp) else 0.dp
    val gutter = LocalPageGutter.current
    val panelSpacing = uiPrefs.tabletPanelSpacing.dp.coerceAtLeast(4.dp)
    val density = LocalDensity.current
    var dockHeight by remember { mutableStateOf(0.dp) }
    val dockVisible = rail && playerState.hasTrack
    val detailId = if (currentRoute == Routes.DETAIL) backStackEntry?.arg("id") else null
    var playlistsVersion by remember { mutableStateOf(0) }
    val sidebarPlaylists by produceState(emptyList<Playlist>(), sessionReady, session?.server, accountEpoch, libraryReload, offlineMode, playlistsVersion) {
        if (sessionReady == true) value = runCatching { repository.allPlaylists() }.getOrNull() ?: value
    }

    var showSpeedSheet by rememberSaveable { mutableStateOf(false) }
    var showVisualizer by rememberSaveable { mutableStateOf(false) }
    var showOutput by rememberSaveable { mutableStateOf(false) }
    var showSleep by rememberSaveable { mutableStateOf(false) }
    var playerPaneRequest by remember { mutableStateOf<PlayerPane?>(null) }
    fun showPane(pane: PlayerPane) {
        if (windowLayout.canShowSidePanel) {
            sidePaneName = if (sidePane == pane && shell.sidePanel) null else pane.name
        } else {
            playerPaneRequest = pane
            player.setExpanded(true)
        }
    }

    val playerColors = rememberPlayerColorScheme(playerState.current.artworkUrl, playerState.current.accent)

    fun navigateTopLevel(route: String) {
        if (navController.currentDestination?.route == route) return
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = false }
            launchSingleTop = true
            restoreState = false
        }
    }

    fun openNavItem(item: NavMenuItem) {
        if (item.topLevel) navigateTopLevel(item.route)
        else if (navController.currentDestination?.route != item.route) navController.navigate(item.route) { launchSingleTop = true }
    }

    fun openDrawer() {
        if (rail && shell.expandedSidebar) {
            navController.navigate(Routes.PROFILE) { launchSingleTop = true }
            return
        }
        if (!drawerNavigationPending && !drawerState.isAnimationRunning) scope.launch { drawerState.open() }
    }
    fun closeDrawerThen(action: () -> Unit) {
        if (drawerNavigationPending) return
        drawerNavigationPending = true
        scope.launch {
            try {
                drawerState.close()
                action()
            } finally {
                drawerNavigationPending = false
            }
        }
    }
    fun openDetail(kind: String, id: String) = navController.navigate(Routes.detail(kind, id))
    fun playAlbum(id: String) = scope.launch {
        repository.detail("album", id)?.let { if (it.tracks.isNotEmpty()) player.playCollection("album", id, it.tracks, 0, it.info.songCount) }
    }
    fun playById(id: String) = scope.launch {
        repository.songFor(id)?.let { player.play(it) }
    }
    fun logout() {
        scope.launch { container.signOut() }
        authVM.reset()
        navController.navigate(Routes.SIGN_IN) { popUpTo(navController.graph.id) { inclusive = true } }
    }

    if (sessionReady == null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            LottieLoader(modifier = Modifier.size(90.dp))
        }
        return
    }
    val startDestination = rememberSaveable { if (sessionReady == true) Routes.HOME else Routes.SIGN_IN }

    LaunchedEffect(sessionReady) {
        if (sessionReady == true && navController.currentDestination?.route == Routes.SIGN_IN) {
            navController.navigate(Routes.HOME) { popUpTo(Routes.SIGN_IN) { inclusive = true } }
        }
    }

    // adding an account while signed in keeps sessionReady true so the account change moves on from sign-in
    LaunchedEffect(accountEpoch) {
        if (accountEpoch > 0 && sessionReady == true && navController.currentDestination?.route == Routes.SIGN_IN) {
            navController.navigate(Routes.HOME) { popUpTo(Routes.SIGN_IN) { inclusive = true } }
        }
    }

    LaunchedEffect(sessionReady, session?.imageUrl) {
        if (sessionReady == true && session != null && session?.imageUrl.isNullOrBlank()) {
            val url = runCatching { repository.profileImageUrl() }.getOrNull()
            if (!url.isNullOrBlank()) store.updateUserImage(url)
        }
    }

    val onShortcut by rememberUpdatedState<(Shortcut) -> Unit> { shortcut ->
        val hasTrack = playerState.hasTrack
        when (shortcut) {
            Shortcut.PLAY_PAUSE -> if (hasTrack) player.togglePlay()
            Shortcut.PREVIOUS -> if (hasTrack) player.previous()
            Shortcut.NEXT -> if (hasTrack) player.next()
            Shortcut.LIKE -> if (hasTrack) player.toggleLikeCurrent()
            Shortcut.SEARCH -> if (showChrome) {
                player.setExpanded(false)
                showVisualizer = false
                navigateTopLevel(Routes.SEARCH)
            }
            Shortcut.QUEUE -> if (hasTrack) {
                if (playerState.expanded) playerPaneRequest = PlayerPane.QUEUE else showPane(PlayerPane.QUEUE)
            }
            Shortcut.FULLSCREEN -> if (fullscreen) onFullscreenChange(false) else if (hasTrack) {
                player.setExpanded(true)
                onFullscreenChange(true)
            }
            Shortcut.BACK -> back()
        }
    }
    LaunchedEffect(shortcuts) { shortcuts.collect { onShortcut(it) } }
    LaunchedEffect(playerState.expanded, fullscreen) {
        if (fullscreen && !player.state.value.expanded) onFullscreenChange(false)
    }

    val nowPlayingPane: @Composable (PlayerPane, Modifier) -> Unit = { pane, modifier ->
        when (pane) {
            PlayerPane.LYRICS -> LyricsPane(playerState, onSeek = { player.seekTo(it) }, modifier = modifier.clip(RoundedCornerShape(20.dp)))
            PlayerPane.QUEUE -> QueueContent(
                queue = playerState.queue,
                currentIndex = playerState.currentIndex,
                isPlaying = playerState.isPlaying,
                onJump = { player.jumpTo(it) },
                onRemove = { player.removeFromQueue(it) },
                onMove = { from, to -> player.moveQueueItem(from, to) },
                editable = !playerState.isMix,
                modifier = modifier.padding(horizontal = 4.dp),
            )
        }
    }
    val nowPlayingPaneActions: @Composable (PlayerPane) -> Unit = { pane ->
        if (pane == PlayerPane.QUEUE) QueueActions(
            queue = playerState.queue,
            currentIndex = playerState.currentIndex,
            editable = !playerState.isMix,
            onClear = { player.clearQueue() },
            onSaveAsPlaylist = { name ->
                player.saveQueueAsPlaylist(name) {
                    confirm(it)
                    playlistsVersion++
                }
            },
        )
    }

    val detailContent: @Composable (String, String, DetailViewModel, PaddingValues, () -> Unit, (String, String) -> Unit) -> Unit =
        { kind, id, detailVM, padding, onBack, onOpen ->
            LaunchedEffect(kind, id) { detailVM.load(kind, id) }
            val detailState by detailVM.state.collectAsStateWithLifecycle()
            LaunchedEffect(detailState.data?.tracks?.size) {
                detailState.data?.tracks?.let { ts -> player.checkLiked(ts.map { it.id }) }
            }
            DetailScreen(
                contentPadding = padding,
                state = detailState,
                likedIds = playerState.likedIds,
                currentSongId = playerState.current.id,
                isPlaying = playerState.isPlaying,
                onBack = onBack,
                onPlayAll = { songs, index -> player.playCollection(kind, id, songs, index, detailState.data?.info?.songCount ?: songs.size) },
                onShufflePlay = { songs -> player.shuffleCollection(kind, id, songs, detailState.data?.info?.songCount ?: songs.size) },
                onAddToQueue = { player.addToQueue(it); confirm(appString(R.string.text_added_to_queue_9d7749)) },
                onPlayNext = { player.playNext(it); confirm(appString(R.string.text_playing_next_b23445)) },
                onToggleLike = { player.toggleLike(it) },
                onOpenDetail = onOpen,
                itemKind = kind,
                isItemLiked = playerState.likedIds.contains(id),
                onToggleItemLike = { player.toggleLike(id, kind) },
                downloadedIds = downloadedIds,
                onDownload = onDownload,
                onRemoveDownload = onRemoveDownload,
                onDownloadAll = {
                    detailState.data?.let { d ->
                        val started = if (kind == "album" || kind == "playlist") {
                            container.downloadManager.downloadCollection(id, kind, d.info.title, d.info.subtitle, d.info.artUrl, d.tracks)
                        } else {
                            container.downloadManager.downloadAll(d.tracks)
                        }
                        val n = d.tracks.count { !repository.isDownloaded(it.id) }
                        confirm(when {
                            !started -> appString(R.string.download_start_failed)
                            n > 0 -> appString(R.string.downloading_tracks, appPlural(R.plurals.track_count, n))
                            else -> appString(R.string.text_already_downloaded_8acc8a)
                        })
                    }
                },
                onRemoveDownloads = {
                    if (kind == "album" || kind == "playlist") repository.removeDownloadedCollection(id, kind)
                    detailState.data?.tracks?.forEach { repository.removeDownload(it.id) }
                },
                onEditPlaylist = { name, desc ->
                    playlistMutation { repository.updatePlaylist(id, name, desc) }.also { updated ->
                        if (updated) {
                            detailVM.reload(kind, id)
                            playlistsVersion++
                        }
                    }
                },
                onDeletePlaylist = {
                    scope.launch {
                        if (playlistMutation { repository.deletePlaylist(id) }) {
                            playlistsVersion++
                            onBack()
                        } else confirm(appString(R.string.playlist_delete_failed))
                    }
                },
                onLoadMore = { detailVM.loadMore() },
                canDownload = !localMode,
                isPinned = pins.any { it.id == id && it.kind == kind },
                onTogglePin = {
                    val info = detailState.data?.info
                    val pin = Pin(
                        id = id, kind = kind,
                        title = info?.title ?: kind,
                        subtitle = info?.subtitle ?: "",
                        coverUrl = info?.artUrl ?: "",
                        serverId = currentServer,
                    )
                    scope.launch { store.togglePin(pin) }
                },
                artistInfo = detailState.artistInfo,
            )
        }

    val settingsGraph: NavGraphBuilder.(NavHostController, PaddingValues) -> Unit = { nav, padding ->
        val pop: () -> Unit = { nav.popBackStack() }
        composable(Routes.SETTINGS_ADVANCED_AUDIO) {
            AdvancedAudioSettingsScreen(padding, onBack = pop, onOpen = { nav.navigate(it.route) }, available = { it.route !in unportedRoutes })
        }
        composable(Routes.SETTINGS_ACCOUNTS) {
            AccountsScreen(
                contentPadding = padding,
                onBack = pop,
                onSwitch = { s ->
                    scope.launch { container.switchSession(s) }
                    confirm(appString(R.string.text_switched_to_c57200, s.typeLabel.localizedMediaType()))
                    nav.popBackStack()
                },
                onForget = { s -> scope.launch { container.forgetSavedSession(s) } },
                onAddAccount = { authVM.reset(); navController.navigate(Routes.SIGN_IN) },
            )
        }
        composable(Routes.SETTINGS_PLAYBACK) {
            PlaybackSettingsScreen(
                contentPadding = padding, onBack = pop,
                onOpenOutput = { nav.navigate(Routes.SETTINGS_OUTPUT) },
                onOpenLoudness = { nav.navigate(Routes.SETTINGS_LOUDNESS) },
                onOpenEq = { nav.navigate(Routes.SETTINGS_EQ) },
            )
        }
        composable(Routes.SIGNAL_PATH) {
            SignalPathScreen(padding, player.signalPath, onBack = pop, onOpenOutput = { nav.navigate(Routes.SETTINGS_OUTPUT) })
        }
        composable(Routes.SETTINGS_OUTPUT) {
            AudioOutputSettingsScreen(padding, onBack = pop, onOpenSignalPath = { nav.navigate(Routes.SIGNAL_PATH) })
        }
        composable(Routes.SETTINGS_LOUDNESS) {
            LoudnessSettingsScreen(padding, onBack = pop, onOpenEq = { nav.navigate(Routes.SETTINGS_EQ) }, onOpenSignalPath = { nav.navigate(Routes.SIGNAL_PATH) })
        }
        composable(Routes.SETTINGS_BACKUP) {
            BackupScreen(padding, container.backupManager, onBack = pop, confirm = { confirm(it) })
        }
        composable(Routes.SETTINGS_INTEGRATIONS) {
            IntegrationsSettingsScreen(
                contentPadding = padding,
                onBack = pop,
                onOpenLyrics = { nav.navigate(Routes.SETTINGS_INTEGRATION_LYRICS) },
                onOpenLastfm = { nav.navigate(Routes.SETTINGS_INTEGRATION_LASTFM) },
                onOpenListenBrainz = { nav.navigate(Routes.SETTINGS_INTEGRATION_LISTENBRAINZ) },
                onOpenArtistInfo = { nav.navigate(Routes.SETTINGS_INTEGRATION_ARTIST_INFO) },
            )
        }
        composable(Routes.SETTINGS_INTEGRATION_LYRICS) { LyricsIntegrationScreen(padding, pop) }
        composable(Routes.SETTINGS_INTEGRATION_LASTFM) { LastfmIntegrationScreen(padding, pop) }
        composable(Routes.SETTINGS_INTEGRATION_LISTENBRAINZ) { ListenBrainzIntegrationScreen(padding, pop) }
        composable(Routes.SETTINGS_INTEGRATION_ARTIST_INFO) { ArtistInfoIntegrationScreen(padding, pop) }
        composable(Routes.SETTINGS_APPEARANCE) {
            AppearanceScreen(padding, onBack = pop, onOpenNavigationMenu = { nav.navigate(Routes.SETTINGS_NAV_MENU) })
        }
        composable(Routes.SETTINGS_NAV_MENU) { NavigationMenuScreen(padding, pop) }
        composable(Routes.SETTINGS_EQ) {
            EqualizerScreen(
                contentPadding = padding, onBack = pop,
                onOpenLoudness = { nav.navigate(Routes.SETTINGS_LOUDNESS) },
                onOpenProcessingPresets = { nav.navigate(Routes.SETTINGS_PROCESSING_PRESETS) },
                onOpenProcessingRack = { nav.navigate(Routes.SETTINGS_PROCESSING_RACK) },
                onOpenImpulses = { nav.navigate(Routes.SETTINGS_IMPULSES) },
            )
        }
        composable(Routes.SETTINGS_PROCESSING_PRESETS) {
            ProcessingPresetsScreen(padding, onBack = pop, onOpenSignalPath = { nav.navigate(Routes.SIGNAL_PATH) })
        }
        composable(Routes.SETTINGS_PROCESSING_RACK) {
            ProcessingRackScreen(
                contentPadding = padding, signalPath = player.signalPath, onBack = pop,
                onOpenPresets = { nav.navigate(Routes.SETTINGS_PROCESSING_PRESETS) },
                onOpenSignalPath = { nav.navigate(Routes.SIGNAL_PATH) },
                onOpenTuning = null,
                onOpenImpulses = { nav.navigate(Routes.SETTINGS_IMPULSES) },
            )
        }
        composable(Routes.SETTINGS_IMPULSES) { ConvolutionLibraryScreen(padding, player.signalPath, pop) }
        composable(Routes.SETTINGS_VISUALIZER) { VisualizerSettingsScreen(padding, pop) }
        composable(Routes.SETTINGS_SOURCES) { SourcesSettingsScreen(padding, onBack = pop, onArtistSeparators = null) }
        composable(Routes.SETTINGS_STORAGE) { StorageSettingsScreen(padding, pop) }
        composable(Routes.SETTINGS_ABOUT) { AboutSettingsScreen(padding, pop) }
        composable(Routes.SETTINGS_LANGUAGE) { LanguageSettingsScreen(padding, pop) }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(drawerState = drawerState, drawerContainerColor = MaterialTheme.colorScheme.surface) {
                TabletSidebar(
                    currentRoute = currentRoute,
                    username = profileAppearance.name,
                    server = session?.server ?: "",
                    avatarUrl = profileAppearance.avatarUrl,
                    layout = navLayout,
                    onOpen = { item -> closeDrawerThen { openNavItem(item) } },
                    onProfile = { closeDrawerThen { navController.navigate(Routes.PROFILE) { launchSingleTop = true } } },
                    onSettings = { closeDrawerThen { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } } },
                    playlists = sidebarPlaylists,
                    pins = pins,
                    selectedId = detailId,
                    onOpenCollection = { kind, id -> closeDrawerThen { openDetail(kind, id) } },
                )
            }
        },
    ) {
        Box(
            Modifier.fillMaxSize().pointerInput(back) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press && event.buttons.isBackPressed) back()
                    }
                }
            },
        ) {
            AmbientBackground()
            if (rail) {
                Box(Modifier.fillMaxHeight().then(if (dockVisible) Modifier.padding(bottom = dockHeight + 12.dp) else Modifier)) {
                    if (shell.expandedSidebar) {
                        TabletSidebar(
                            currentRoute = currentRoute,
                            username = profileAppearance.name,
                            server = session?.server ?: "",
                            avatarUrl = profileAppearance.avatarUrl,
                            layout = navLayout,
                            onOpen = { openNavItem(it) },
                            onProfile = { navController.navigate(Routes.PROFILE) { launchSingleTop = true } },
                            onSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                            playlists = sidebarPlaylists,
                            pins = pins,
                            selectedId = detailId,
                            onOpenCollection = { kind, id -> openDetail(kind, id) },
                        )
                    } else {
                        TabletNavigationRail(currentRoute, navLayout.main, { openNavItem(it) }, { openDrawer() }) {
                            navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                        }
                    }
                }
            }
            Scaffold(
                modifier = Modifier.padding(start = if (rail) shell.navWidth + navGap else 0.dp)
                    .then(if (dockVisible) Modifier.padding(bottom = dockHeight + 12.dp + panelSpacing) else Modifier),
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    if (showChrome && (activeDownloads > 0 || offlineMode)) {
                        Column(
                            Modifier.fillMaxWidth()
                                .padding(start = gutter, end = gutter, top = 8.dp, bottom = if (dockVisible) 0.dp else 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (activeDownloads > 0) {
                                DownloadProgressBanner(count = activeDownloads, progress = downloadProgress)
                                Spacer(Modifier.height(8.dp))
                            }
                            if (offlineMode) {
                                Row(
                                    Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                                        .padding(horizontal = 14.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Filled.CloudOff, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(appString(R.string.text_offline_mode_66cf31), style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                },
            ) { inner ->
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        NavHost(
                            navController = navController,
                            startDestination = startDestination,
                            modifier = Modifier.fillMaxSize(),
                            enterTransition = { EnterTransition.None },
                            exitTransition = { ExitTransition.None },
                            popEnterTransition = { EnterTransition.None },
                            popExitTransition = { ExitTransition.None },
                        ) {
                            composable(Routes.SIGN_IN) {
                                SignInScreen(
                                    state = authState,
                                    onSelectType = authVM::selectType,
                                    onScheme = authVM::onScheme,
                                    onHost = authVM::onHost,
                                    onUsername = authVM::onUsername,
                                    onPassword = authVM::onPassword,
                                    onBack = authVM::back,
                                    canContinueServer = authVM.canContinueServer,
                                    onContinueServer = authVM::continueToCredentials,
                                    canSubmit = authVM.canSubmit,
                                    onSignIn = {
                                        authVM.signIn {
                                            navController.navigate(Routes.HOME) { popUpTo(Routes.SIGN_IN) { inclusive = true } }
                                        }
                                    },
                                    onLocal = authVM::signInLocal,
                                    musicFolders = musicFolders,
                                    onAddFolder = authVM::addMusicFolder,
                                    onRemoveFolder = authVM::removeMusicFolder,
                                    savedSessions = savedSessions,
                                    onUseSaved = { authVM.useSaved(it) },
                                )
                            }
                            composable(Routes.NOTIFICATIONS) {
                                RecapInboxScreen(inner, { navController.popBackStack() }) {
                                    navController.navigate("recap/${it.period.name}/${it.start}")
                                }
                            }
                            composable("recap/{period}/{date}") { entry ->
                                val period = runCatching { RecapPeriod.valueOf(entry.arg("period")) }.getOrDefault(RecapPeriod.DAY)
                                val date = runCatching { LocalDate.parse(entry.arg("date")) }.getOrDefault(LocalDate.now().minusDays(1))
                                ListeningStatsScreen(inner, { navController.popBackStack() }, { playById(it) }, { k, i -> openDetail(k, i) },
                                    RecapWindow.containing(period, date))
                            }
                            composable(Routes.HOME) {
                                val homeVM = viewModel { HomeViewModel(container) }
                                val homeState by homeVM.state.collectAsStateWithLifecycle()
                                HomeScreen(
                                    contentPadding = inner,
                                    state = homeState,
                                    username = profileAppearance.name,
                                    avatarUrl = profileAppearance.avatarUrl,
                                    onOpenDrawer = { openDrawer() },
                                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                                    onOpenDetail = { kind, id -> openDetail(kind, id) },
                                    onPlayAlbum = { playAlbum(it) },
                                    onPlayAll = { songs, index -> player.playAll(songs, index) },
                                    onLoadMore = homeVM::loadMore,
                                    onRetry = { homeVM.load() },
                                    onSelectFeed = homeVM::selectFeed,
                                    onOpenNotifications = { navController.navigate(Routes.NOTIFICATIONS) },
                                    onAddSource = { authVM.reset(); navController.navigate(Routes.SIGN_IN) },
                                )
                            }
                            composable(Routes.SEARCH) {
                                val searchVM = viewModel(viewModelStoreOwner = rootOwner) { SearchViewModel(container) }
                                val searchState by searchVM.state.collectAsStateWithLifecycle()
                                val recentSearches by searchVM.recentSearches.collectAsStateWithLifecycle()
                                LaunchedEffect(searchState.results.songs.size) {
                                    player.checkLiked(searchState.results.songs.map { it.id })
                                }
                                SearchScreen(
                                    contentPadding = inner,
                                    state = searchState,
                                    likedIds = playerState.likedIds,
                                    currentSongId = playerState.current.id,
                                    isPlaying = playerState.isPlaying,
                                    onQuery = searchVM::onQuery,
                                    onSelectSource = searchVM::selectSource,
                                    onPlayAll = { songs, index -> player.playAll(songs, index) },
                                    onAddToQueue = { player.addToQueue(it); confirm(appString(R.string.text_added_to_queue_9d7749)) },
                                    onPlayNext = { player.playNext(it); confirm(appString(R.string.text_playing_next_b23445)) },
                                    onToggleLike = { player.toggleLike(it) },
                                    onOpenDetail = { kind, id -> openDetail(kind, id) },
                                    downloadedIds = downloadedIds,
                                    onDownload = onDownload,
                                    onRemoveDownload = onRemoveDownload,
                                    canDownload = !localMode,
                                    recentSearches = recentSearches,
                                    onRecentClick = { searchVM.onQuery(it) },
                                    onRemoveRecent = { searchVM.removeRecent(it) },
                                    onClearRecents = { searchVM.clearRecents() },
                                    onCommitSearch = { searchVM.commit() },
                                )
                            }
                            composable(Routes.LIBRARY) {
                                val libraryVM = viewModel { LibraryViewModel(container) }
                                val libraryState by libraryVM.state.collectAsStateWithLifecycle()
                                var librarySelection by rememberSaveable { mutableStateOf<String?>(null) }
                                BoxWithConstraints(Modifier.fillMaxSize()) {
                                    val split = rail && maxWidth >= 960.dp
                                    val selected = librarySelection?.split(":", limit = 2)?.takeIf { split && it.size == 2 }
                                    BackHandler(enabled = split && librarySelection != null) { librarySelection = null }
                                    val listWidth = if (maxWidth >= 1500.dp) 560.dp else 440.dp
                                    Row(Modifier.fillMaxSize()) {
                                        Box(if (selected != null) Modifier.width(listWidth).fillMaxHeight() else Modifier.weight(1f).fillMaxHeight()) {
                                            LibraryScreen(
                                                contentPadding = inner,
                                                state = libraryState,
                                                username = profileAppearance.name,
                                                likedIds = playerState.likedIds,
                                                currentSongId = playerState.current.id,
                                                isPlaying = playerState.isPlaying,
                                                onFilter = libraryVM::setFilter,
                                                onSort = libraryVM::setSort,
                                                onToggleLayout = libraryVM::toggleLayout,
                                                onOpenDrawer = { openDrawer() },
                                                onPlayAll = { songs, index -> player.playAll(songs, index) },
                                                onAddToQueue = { player.addToQueue(it); confirm(appString(R.string.text_added_to_queue_9d7749)) },
                                                onPlayNext = { player.playNext(it); confirm(appString(R.string.text_playing_next_b23445)) },
                                                onToggleLike = { player.toggleLike(it) },
                                                onOpenDetail = { kind, id -> if (split && kind in splitKinds) librarySelection = "$kind:$id" else openDetail(kind, id) },
                                                downloadedIds = downloadedIds,
                                                onDownload = onDownload,
                                                onRemoveDownload = onRemoveDownload,
                                                onOpenSearch = { navigateTopLevel(Routes.SEARCH) },
                                                onCreatePlaylist = { name ->
                                                    playlistMutation { repository.createPlaylist(name) }.also { created ->
                                                        if (created) {
                                                            libraryVM.load()
                                                            playlistsVersion++
                                                        }
                                                    }
                                                },
                                                onCreateSmart = { navController.navigate(Routes.smartEdit()) },
                                                onEditSmart = { id -> navController.navigate(Routes.smartEdit(id)) },
                                                onDeleteSmart = { id -> scope.launch { store.deleteSmartPlaylist(id) } },
                                                onImportM3u = {
                                                    FilePickers.openFile(appString(R.string.text_import_an_m3u_file_3ae427), listOf("m3u", "m3u8"))?.let { file ->
                                                        scope.launch {
                                                            val text = withContext(Dispatchers.IO) { runCatching { file.readText() }.getOrNull() }
                                                            val entries = text?.let { M3u.parse(it) }.orEmpty()
                                                            if (entries.isEmpty()) {
                                                                confirm(appString(R.string.text_no_tracks_found_in_that_file_754434))
                                                            } else {
                                                                val name = file.nameWithoutExtension.ifBlank { appString(R.string.text_imported_playlist_37a186) }
                                                                confirm(appString(R.string.text_importing_tracks_3abc1a, entries.size))
                                                                val result = repository.importPlaylist(name, entries)
                                                                if (result == null) confirm(appString(R.string.text_import_failed_fbae89)) else {
                                                                    confirm(appString(R.string.text_matched_of_tracks_09b8b1, result.first, result.second))
                                                                    libraryVM.load()
                                                                    playlistsVersion++
                                                                }
                                                            }
                                                        }
                                                    }
                                                },
                                                onExportPlaylist = { id, kind, title ->
                                                    scope.launch {
                                                        val text = repository.exportPlaylist(kind, id)
                                                        if (text == null) {
                                                            confirm(appString(R.string.text_nothing_to_export_7faf33))
                                                            return@launch
                                                        }
                                                        val file = FilePickers.saveFile(appString(R.string.text_export_as_m3u_5189ed),
                                                            "${title.replace(Regex("[\\\\/:*?\"<>|]"), "_")}.m3u8") ?: return@launch
                                                        val saved = withContext(Dispatchers.IO) { runCatching { file.writeText(text) }.isSuccess }
                                                        confirm(if (saved) appString(R.string.text_playlist_exported_0efbda) else appString(R.string.text_export_failed_d6c17e))
                                                    }
                                                },
                                                onOpenFolders = { navController.navigate(Routes.folders()) },
                                                onPlayCollection = { id, kind ->
                                                    scope.launch {
                                                        repository.detail(kind, id)?.let { d -> if (d.tracks.isNotEmpty()) player.playCollection(kind, id, d.tracks, 0, d.info.songCount) }
                                                    }
                                                },
                                                onShuffleCollection = { id, kind ->
                                                    scope.launch {
                                                        repository.detail(kind, id)?.let { d -> if (d.tracks.isNotEmpty()) player.shuffleCollection(kind, id, d.tracks, d.info.songCount) }
                                                    }
                                                },
                                                onQueueCollection = { id, kind ->
                                                    scope.launch {
                                                        val tracks = repository.detail(kind, id)?.tracks.orEmpty()
                                                        tracks.forEach { player.addToQueue(it) }
                                                        if (tracks.isNotEmpty()) confirm(appString(R.string.text_added_to_queue_88e96c, tracks.size))
                                                    }
                                                },
                                                onToggleLikeKind = { id, kind -> player.toggleLike(id, kind) },
                                                onDeletePlaylist = { id ->
                                                    scope.launch {
                                                        if (playlistMutation { repository.deletePlaylist(id) }) {
                                                            libraryVM.load()
                                                            playlistsVersion++
                                                        } else confirm(appString(R.string.playlist_delete_failed))
                                                    }
                                                },
                                                canDownload = !localMode,
                                                pins = pins,
                                                onLoadMoreSongs = libraryVM::loadMoreSongs,
                                                onPlayAllSongs = { shuffle ->
                                                    scope.launch {
                                                        val all = libraryVM.fullSortedSongs()
                                                        if (all.isEmpty()) return@launch
                                                        if (shuffle) player.shufflePlay(all) else player.playAll(all, 0)
                                                    }
                                                },
                                                onPlaySong = { song ->
                                                    scope.launch {
                                                        val all = libraryVM.fullSortedSongs()
                                                        val index = all.indexOfFirst { it.id == song.id }
                                                        if (index >= 0) player.playAll(all, index) else player.playAll(listOf(song), 0)
                                                    }
                                                },
                                                selectedItem = if (split) librarySelection else null,
                                            )
                                        }
                                        if (selected != null) {
                                            Box(
                                                Modifier.weight(1f).fillMaxHeight().padding(end = TabletMetrics.WindowInset)
                                                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)),
                                            ) {
                                                val paneVM = viewModel(key = "library-detail") { DetailViewModel(container) }
                                                CompositionLocalProvider(LocalPageGutter provides PageMetrics.PaneGutter) {
                                                    detailContent(selected[0], selected[1], paneVM, inner, { librarySelection = null }) { k, i ->
                                                        if (k in listOf("album", "artist", "playlist")) librarySelection = "$k:$i" else openDetail(k, i)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            composable(
                                Routes.FOLDERS,
                                arguments = listOf(navArgument("fid") { defaultValue = "" }, navArgument("title") { defaultValue = "" }),
                            ) { entry ->
                                val fid = entry.arg("fid")
                                val folderVM = viewModel { FolderViewModel(container) }
                                LaunchedEffect(fid) { folderVM.load(fid) }
                                val folderState by folderVM.state.collectAsStateWithLifecycle()
                                LaunchedEffect(folderState.content?.songs?.size) {
                                    folderState.content?.songs?.let { ss -> player.checkLiked(ss.map { it.id }) }
                                }
                                FolderScreen(
                                    contentPadding = inner,
                                    title = entry.arg("title"),
                                    loading = folderState.loading,
                                    content = folderState.content,
                                    likedIds = playerState.likedIds,
                                    currentSongId = playerState.current.id,
                                    isPlaying = playerState.isPlaying,
                                    onBack = { navController.popBackStack() },
                                    onOpenFolder = { id, name -> navController.navigate(Routes.folders(id, name)) },
                                    onPlayAll = { songs, index -> player.playAll(songs, index) },
                                    onShufflePlay = { songs -> player.shufflePlay(songs) },
                                    onAddToQueue = { player.addToQueue(it); confirm(appString(R.string.text_added_to_queue_9d7749)) },
                                    onPlayNext = { player.playNext(it); confirm(appString(R.string.text_playing_next_b23445)) },
                                    onToggleLike = { player.toggleLike(it) },
                                    onOpenDetail = { k, i -> openDetail(k, i) },
                                    downloadedIds = downloadedIds,
                                    onDownload = onDownload,
                                    onRemoveDownload = onRemoveDownload,
                                    canDownload = !localMode,
                                )
                            }
                            composable(Routes.SMART_EDIT, arguments = listOf(navArgument("id") { defaultValue = "" })) { entry ->
                                val smartId = entry.arg("id")
                                val smartVM = viewModel { SmartPlaylistViewModel(container) }
                                LaunchedEffect(smartId) { smartVM.load(smartId) }
                                val smartState by smartVM.state.collectAsStateWithLifecycle()
                                SmartPlaylistEditScreen(
                                    contentPadding = inner,
                                    playlist = smartState,
                                    isNew = smartId.isBlank(),
                                    onUpdate = smartVM::update,
                                    onSave = { smartVM.save { navController.popBackStack() } },
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable(Routes.PROFILE) {
                                var editing by remember { mutableStateOf(false) }
                                if (editing && localMode) LocalProfileDialog(onDismiss = { editing = false })
                                val homeVM = viewModel { HomeViewModel(container) }
                                val homeState by homeVM.state.collectAsStateWithLifecycle()
                                ProfileScreen(
                                    contentPadding = inner,
                                    username = profileAppearance.name,
                                    server = session?.server ?: "",
                                    serverLabel = session?.typeLabel?.localizedMediaType() ?: "",
                                    avatarUrl = profileAppearance.avatarUrl,
                                    bannerUrl = profileAppearance.bannerUrl,
                                    onEditProfile = if (localMode) ({ editing = true }) else null,
                                    playlists = homeState.data.playlists,
                                    artists = homeState.data.artists,
                                    onBack = { navController.popBackStack() },
                                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                                    onOpenDetail = { kind, id -> openDetail(kind, id) },
                                )
                            }
                            composable(Routes.DETAIL) { entry ->
                                detailContent(entry.arg("kind"), entry.arg("id"), viewModel { DetailViewModel(container) }, inner,
                                    { navController.popBackStack() }, { k, i -> openDetail(k, i) })
                            }
                            composable(Routes.SETTINGS) {
                                val paneNav = rememberNavController()
                                val paneRoots = remember(paneNav) {
                                    val roots = HashSet<String>()
                                    paneNav.addOnDestinationChangedListener { nav, _, _ ->
                                        val entry = nav.currentBackStackEntry
                                        if (entry != null && nav.previousBackStackEntry == null) roots += entry.id
                                    }
                                    roots
                                }
                                var paneRoute by rememberSaveable { mutableStateOf(Routes.SETTINGS_PLAYBACK) }
                                var paneTouched by rememberSaveable { mutableStateOf(false) }
                                BoxWithConstraints(Modifier.fillMaxSize()) {
                                    val twoPane = maxWidth >= 840.dp
                                    fun open(route: String) {
                                        if (twoPane) {
                                            paneRoute = route
                                            paneTouched = true
                                            paneNav.navigate(route) { popUpTo(paneNav.graph.id) { inclusive = true }; launchSingleTop = true }
                                        } else navController.navigate(route)
                                    }
                                    val advanced = setOf(Routes.SETTINGS_LOUDNESS, Routes.SETTINGS_ADVANCED_AUDIO, Routes.SIGNAL_PATH)
                                    LaunchedEffect(simpleMode, paneRoute, twoPane) {
                                        if (twoPane && simpleMode && paneRoute in advanced) open(Routes.SETTINGS_PLAYBACK)
                                    }
                                    LaunchedEffect(twoPane) {
                                        if (!twoPane && paneTouched && currentRoute == Routes.SETTINGS) {
                                            paneTouched = false
                                            navController.navigate(paneRoute)
                                        }
                                    }
                                    val listWidth = if (maxWidth >= 1300.dp) 380.dp else 320.dp
                                    Row(Modifier.fillMaxSize()) {
                                        Box(if (twoPane) Modifier.width(listWidth).fillMaxHeight() else Modifier.weight(1f).fillMaxHeight()) {
                                            CompositionLocalProvider(LocalSelectedSettingsRoute provides if (twoPane) paneRoute else null) {
                                                SettingsScreen(
                                                    contentPadding = inner,
                                                    username = profileAppearance.name,
                                                    server = session?.server ?: "",
                                                    avatarUrl = profileAppearance.avatarUrl,
                                                    onBack = { navController.popBackStack() },
                                                    onOpenPlayback = { open(Routes.SETTINGS_PLAYBACK) },
                                                    onOpenOutput = { open(Routes.SETTINGS_OUTPUT) },
                                                    onOpenLoudness = { open(Routes.SETTINGS_LOUDNESS) },
                                                    onOpenAdvancedAudio = { open(Routes.SETTINGS_ADVANCED_AUDIO) },
                                                    onOpenSignalPath = { open(Routes.SIGNAL_PATH) },
                                                    onOpenEq = { open(Routes.SETTINGS_EQ) },
                                                    onOpenVisualizer = { open(Routes.SETTINGS_VISUALIZER) },
                                                    onOpenSonic = null,
                                                    onOpenSources = { open(Routes.SETTINGS_SOURCES) },
                                                    onOpenDownloads = { open(Routes.SETTINGS_STORAGE) },
                                                    onOpenAppearance = { open(Routes.SETTINGS_APPEARANCE) },
                                                    onOpenLanguage = { open(Routes.SETTINGS_LANGUAGE) },
                                                    onOpenIntegrations = { open(Routes.SETTINGS_INTEGRATIONS) },
                                                    onOpenAbout = { open(Routes.SETTINGS_ABOUT) },
                                                    onOpenProfile = { navController.navigate(Routes.PROFILE) },
                                                    onOpenAccounts = { open(Routes.SETTINGS_ACCOUNTS) },
                                                    onOpenBackup = { open(Routes.SETTINGS_BACKUP) },
                                                    onLogout = { logout() },
                                                )
                                            }
                                        }
                                        if (twoPane) {
                                            Box(
                                                Modifier.weight(1f).fillMaxHeight().padding(end = TabletMetrics.WindowInset)
                                                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)),
                                            ) {
                                                CompositionLocalProvider(LocalSettingsPaneRoots provides paneRoots, LocalPageGutter provides PageMetrics.PaneGutter) {
                                                    NavHost(
                                                        navController = paneNav,
                                                        startDestination = paneRoute,
                                                        modifier = Modifier.fillMaxSize(),
                                                        enterTransition = { fadeIn(tween(160)) },
                                                        exitTransition = { fadeOut(tween(120)) },
                                                        popEnterTransition = { fadeIn(tween(160)) },
                                                        popExitTransition = { fadeOut(tween(120)) },
                                                    ) { settingsGraph(paneNav, inner) }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            settingsGraph(navController, inner)
                            composable(Routes.HISTORY) {
                                ListeningHistoryScreen(contentPadding = inner, onBack = { navController.popBackStack() }, onPlay = { playById(it) })
                            }
                            composable(Routes.DUPLICATES) {
                                val dupVM = viewModel { DuplicatesViewModel(container) }
                                val dupState by dupVM.state.collectAsStateWithLifecycle()
                                DuplicatesScreen(
                                    contentPadding = inner,
                                    loading = dupState.loading,
                                    scanned = dupState.scanned,
                                    groups = dupState.groups,
                                    currentSongId = playerState.current.id,
                                    onBack = { navController.popBackStack() },
                                    onPlay = { s -> player.playAll(listOf(s), 0) },
                                )
                            }
                            composable(Routes.STATS) {
                                ListeningStatsScreen(contentPadding = inner, onBack = { navController.popBackStack() }, onPlay = { playById(it) },
                                    onOpenDetail = { k, i -> openDetail(k, i) })
                            }
                        }
                    }
                    if (rail) {
                        AnimatedVisibility(
                            visible = shell.sidePanel,
                            enter = expandHorizontally(tween(240)) + fadeIn(tween(200)),
                            exit = shrinkHorizontally(tween(220)) + fadeOut(tween(160)),
                        ) {
                            NowPlayingSidePanel(
                                pane = sidePane ?: PlayerPane.QUEUE,
                                onSelect = { sidePaneName = it.name },
                                onClose = { sidePaneName = null },
                                content = { target, modifier -> nowPlayingPane(target, modifier) },
                                actions = { target -> nowPlayingPaneActions(target) },
                                modifier = Modifier.width(TabletMetrics.SidePanelWidth).fillMaxHeight()
                                    .padding(top = panelSpacing, end = TabletMetrics.WindowInset, bottom = inner.calculateBottomPadding()),
                            )
                        }
                    }
                }
            }
            if (dockVisible) {
                PlaybackDock(
                    state = playerState,
                    openPane = if (shell.sidePanel) sidePane else null,
                    volume = volume,
                    onExpand = { player.setExpanded(true) },
                    onTogglePlay = { player.togglePlay() },
                    onPrevious = { player.previous() },
                    onNext = { player.next() },
                    onSeek = { player.seekTo(it) },
                    onToggleLike = { player.toggleLikeCurrent() },
                    onToggleShuffle = { player.toggleShuffle() },
                    onCycleRepeat = { player.cycleRepeat() },
                    onVolumeChange = { player.setVolume(it) },
                    onToggleMute = { player.toggleMute() },
                    onOpenOutput = { showOutput = true },
                    onPane = { showPane(it) },
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                        .onSizeChanged { dockHeight = with(density) { it.height.toDp() } },
                )
            }

            MaterialTheme(colorScheme = playerColors) {
                // registered when shown so it outranks the nav hosts yet stays below the player's own lyrics handler
                if (playerState.expanded) BackHandler { player.setExpanded(false) }
                AnimatedVisibility(
                    visible = playerState.expanded,
                    enter = slideInVertically(animationSpec = tween(320)) { it } + fadeIn(tween(220)),
                    exit = slideOutVertically(animationSpec = tween(280)) { it } + fadeOut(tween(180)),
                ) {
                    PlayerScreen(
                        state = playerState,
                        onCollapse = { player.setExpanded(false) },
                        onTogglePlay = { player.togglePlay() },
                        onNext = { player.next() },
                        onPrevious = { player.previous() },
                        onSeek = { player.seekTo(it) },
                        onToggleLike = { player.toggleLikeCurrent() },
                        onToggleShuffle = { player.toggleShuffle() },
                        onCycleRepeat = { player.cycleRepeat() },
                        onOpenSpeedPitch = { showSpeedSheet = true },
                        onGoToAlbum = {
                            val id = playerState.current.albumId
                            if (id.isNotBlank()) { player.setExpanded(false); openDetail("album", id) }
                        },
                        onGoToArtist = {
                            val id = playerState.current.artistId
                            if (id.isNotBlank()) { player.setExpanded(false); openDetail("artist", id) }
                        },
                        onOpenOutput = { showOutput = true },
                        onOpenSleep = { showSleep = true },
                        onOpenVisualizer = { showVisualizer = true },
                        onOpenSignalPath = {
                            player.setExpanded(false)
                            showSpeedSheet = false
                            showOutput = false
                            showSleep = false
                            navController.navigate(Routes.SIGNAL_PATH) { launchSingleTop = true }
                        },
                        onSonicRadio = { player.startSonicRadio(onResult = { confirm(it) }) },
                        onAutoDj = { player.startAutoDj(onResult = { confirm(it) }) },
                        paneContent = nowPlayingPane,
                        gestures = mouseGestures,
                        requestedPane = playerPaneRequest,
                        onPaneRequestHandled = { playerPaneRequest = null },
                        paneActions = nowPlayingPaneActions,
                        onSplitChange = { split -> scope.launch { store.setTabletSetting(TabletSetting.PLAYER_SPLIT, split) } },
                    )
                }
            }

            AnimatedVisibility(visible = showVisualizer, enter = fadeIn(tween(220)), exit = fadeOut(tween(180))) {
                VisualizerScreen(state = playerState, onClose = { showVisualizer = false })
            }
        }
    }

    MaterialTheme(colorScheme = playerColors) {
        if (showSpeedSheet) {
            SpeedPitchSheet(
                speed = playerState.speed,
                onSpeed = { player.setSpeed(it) },
                onReset = { player.resetSpeedPitch() },
                onDismiss = { showSpeedSheet = false },
            )
        }
        if (showOutput) {
            val outputs by player.outputs.collectAsStateWithLifecycle()
            val preferred by player.preferredOutput.collectAsStateWithLifecycle()
            val exclusive by player.exclusiveOutput.collectAsStateWithLifecycle()
            OutputDeviceSheet(
                devices = outputs,
                currentId = preferred,
                exclusive = exclusive,
                volume = volume,
                onSelect = { player.setPreferredDevice(it) },
                onExclusiveChange = { player.setExclusiveOutput(it) },
                onVolumeChange = { player.setVolume(it) },
                onToggleMute = { player.toggleMute() },
                onDismiss = { showOutput = false },
            )
        }
        if (showSleep) {
            SleepTimerSheet(
                currentMinutes = playerState.sleepTimerMinutes,
                endOfTrack = playerState.sleepEndOfTrack,
                onSelect = { player.setSleepTimer(it) },
                onEndOfTrack = { player.setSleepEndOfTrack() },
                onDismiss = { showSleep = false },
            )
        }
    }
}

private fun NavLayout.ported() = NavLayout(main.filterNot { it.route in unportedRoutes }, more.filterNot { it.route in unportedRoutes }, hidden)

private fun NavBackStackEntry.arg(name: String): String = arguments?.read { getStringOrNull(name) }.orEmpty()

@Composable
private fun DownloadProgressBanner(count: Int, progress: Float) {
    val classic = LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA
    val animated by animateFloatAsState(targetValue = progress.coerceIn(0f, 1f), animationSpec = tween(300), label = "dlProgress")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (classic) Modifier.clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f))
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(18.dp))
                else Modifier.auroraPanel(MaterialTheme.shapes.large))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Downloading, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (count == 1) appString(R.string.text_downloading_1_song_75e402) else appString(R.string.text_downloading_songs_f16c02, count),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { animated },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text("${(animated * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}

private suspend fun playlistMutation(block: suspend () -> Boolean): Boolean = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    false
}
