package com.aurora.music.ui

import com.aurora.music.localization.localizedMediaType

import com.aurora.music.localization.appPlural

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import com.aurora.music.ui.components.PlaybackDock
import com.aurora.music.ui.components.TabletNavigationRail
import com.aurora.music.ui.components.TabletSidebar
import com.aurora.music.ui.layout.TabletMetrics
import com.aurora.music.ui.layout.shell
import com.aurora.music.ui.screens.player.LyricsPane
import com.aurora.music.ui.screens.player.NowPlayingSidePanel
import com.aurora.music.ui.screens.player.PlayerPane
import com.aurora.music.ui.screens.player.QueueContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import com.aurora.music.ui.layout.LocalWindowLayout
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aurora.music.AuroraApplication
import com.aurora.music.navigation.Routes
import com.aurora.music.navigation.topLevelDestinations
import com.aurora.music.ui.components.AmbientBackground
import com.aurora.music.ui.components.MiniPlayer
import com.aurora.music.ui.components.SidebarContent
import com.aurora.music.ui.screens.auth.SignInScreen
import com.aurora.music.ui.screens.detail.DetailScreen
import com.aurora.music.ui.screens.home.HomeScreen
import com.aurora.music.ui.screens.library.LibraryScreen
import com.aurora.music.ui.screens.player.PlayerScreen
import com.aurora.music.ui.screens.player.SpeedPitchSheet
import com.aurora.music.ui.screens.profile.ProfileScreen
import com.aurora.music.ui.screens.search.SearchScreen
import com.aurora.music.ui.theme.auroraPanel
import com.aurora.music.ui.theme.rememberPlayerColorScheme
import com.aurora.music.ui.screens.settings.PlaybackSettingsScreen
import com.aurora.music.ui.screens.settings.SettingsScreen
import com.aurora.music.ui.onboarding.AuroraOnboarding
import com.aurora.music.ui.onboarding.ListeningMode
import com.aurora.music.ui.onboarding.SetupPhase
import com.aurora.music.ui.onboarding.SetupSource
import com.aurora.music.ui.onboarding.SetupTask
import com.aurora.music.ui.onboarding.rememberOnboardingState
import com.aurora.music.viewmodel.AuthViewModel
import com.aurora.music.viewmodel.DetailViewModel
import com.aurora.music.viewmodel.HomeViewModel
import com.aurora.music.viewmodel.LibraryViewModel
import com.aurora.music.viewmodel.PlayerViewModel
import com.aurora.music.viewmodel.SearchViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.aurora.music.model.accent

@Composable
fun AuroraApp() {
    val context = LocalContext.current
    val imeVisible = WindowInsets.ime.asPaddingValues().calculateBottomPadding() > 120.dp
    val container = (context.applicationContext as AuroraApplication).container

    val navController = rememberNavController()
    val playerVM: PlayerViewModel = viewModel()
    val activityOwner = checkNotNull(androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner.current)
    val authVM: AuthViewModel = viewModel()

    val playerState by playerVM.state.collectAsStateWithLifecycle()
    val authState by authVM.state.collectAsStateWithLifecycle()
    val sessionReady by container.sessionReady.collectAsStateWithLifecycle()
    val onboarding = rememberOnboardingState()
    androidx.compose.runtime.LaunchedEffect(container) {
        if (onboarding.phase == SetupPhase.HIDDEN && container.settingsStore.onboardingSeen.first() == null) {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (packageInfo.firstInstallTime == packageInfo.lastUpdateTime) {
                onboarding.restart()
                navController.navigate(Routes.SETUP) { launchSingleTop = true }
            }
            else container.settingsStore.setOnboardingSeen()
        }
    }
    val session by container.settingsStore.session.collectAsStateWithLifecycle(initialValue = null)
    val savedSessions by container.settingsStore.savedSessions.collectAsStateWithLifecycle(initialValue = emptyList())
    val downloadsMap by container.downloadManager.downloads.collectAsStateWithLifecycle()
    val localMode = session?.type == com.aurora.music.data.ServerType.LOCAL
    val localAppearance by container.localProfileAppearance.collectAsStateWithLifecycle()
    val profileAppearance = com.aurora.music.data.profileAppearance(session, localAppearance)
    val serverTagEditing = session?.let { container.repository.supportsServerTagEdit } ?: false
    // pins scoped to the active connection
    val currentServer = session?.server ?: ""
    val allPins by container.settingsStore.pins.collectAsStateWithLifecycle(initialValue = emptyList())
    val pins = allPins.filter { it.serverId == currentServer }
    val gesturePrefs by container.settingsStore.gesturePrefs.collectAsStateWithLifecycle(initialValue = com.aurora.music.data.GesturePrefs())
    val offlineMode by container.offline.collectAsStateWithLifecycle()
    val downloadAccountEpoch by container.accountEpoch.collectAsStateWithLifecycle()
    val downloadLibraryEpoch by container.libraryReload.collectAsStateWithLifecycle()
    val downloadedIds = remember(downloadsMap, session, sessionReady, offlineMode, downloadAccountEpoch, downloadLibraryEpoch) {
        container.repository.downloadedIds()
    }

    val downloadStates by container.downloadManager.states.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    androidx.compose.runtime.LaunchedEffect(container) {
        container.sourceErrors.collect { message ->
            if (snackbarHostState.currentSnackbarData == null) snackbarHostState.showSnackbar(message)
        }
    }
    val scope = rememberCoroutineScope()
    fun confirm(message: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message, duration = androidx.compose.material3.SnackbarDuration.Short)
        }
    }

    val onDownload: (com.aurora.music.model.Song) -> Unit = {
        val already = container.repository.isDownloaded(it.id)
        val started = container.downloadManager.downloadSong(it)
        confirm(when {
            already -> appString(R.string.text_already_downloaded_8acc8a)
            !started -> appString(R.string.download_start_failed)
            else -> appString(R.string.text_downloading_7f12a1, (it.title))
        })
    }
    val onRemoveDownload: (String) -> Unit = {
        if (container.repository.removeDownload(it)) confirm(appString(R.string.text_removed_download_8ad8f9))
    }

    // re-pull likes on foreground so stars from other devices show up
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) playerVM.refreshLikes()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // active means still queued or in flight
    val activeDownloads = downloadStates.values.count {
        it is com.aurora.music.data.DownloadState.Queued || it is com.aurora.music.data.DownloadState.Downloading
    }
    val downloadProgress = downloadStates.values
        .mapNotNull { (it as? com.aurora.music.data.DownloadState.Downloading)?.progress }
        .let { if (it.isEmpty()) 0f else it.sum() / activeDownloads.coerceAtLeast(1) }
    // announce when a batch finishes active falls back to zero
    var hadActiveDownloads by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(activeDownloads) {
        if (activeDownloads > 0) {
            hadActiveDownloads = true
        } else if (hadActiveDownloads) {
            hadActiveDownloads = false
            val failed = downloadStates.values.count { it is com.aurora.music.data.DownloadState.Failed }
            snackbarHostState.showSnackbar(
                if (failed > 0) appString(R.string.text_download_finished_failed_21e6b1, (failed)) else appString(R.string.text_download_complete_95efb3),
                duration = androidx.compose.material3.SnackbarDuration.Short,
            )
        }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var drawerNavigationPending by remember { mutableStateOf(false) }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    androidx.compose.runtime.LaunchedEffect(savedSessions, onboarding.phase, onboarding.connectingSource) {
        val source = onboarding.connectingSource
        if (onboarding.phase == SetupPhase.AUTH && source != null && savedSessions.any { it.type == source.type }) {
            onboarding.connectingSource = null
            onboarding.phase = SetupPhase.SOURCES
            navController.navigate(Routes.SETUP) { launchSingleTop = true }
        }
    }
    fun finishOnboarding() {
        onboarding.phase = SetupPhase.HIDDEN
        onboarding.activeTask = null
        onboarding.connectingSource = null
        scope.launch { container.settingsStore.setOnboardingSeen() }
        val destination = if (sessionReady == true) Routes.HOME else Routes.SIGN_IN
        if (currentRoute != destination) navController.navigate(destination) { popUpTo(0) }
    }
    fun leaveOnboardingAuth(skip: Boolean) {
        if (skip) onboarding.connectingSource?.let { onboarding.skippedSources = onboarding.skippedSources + it }
        onboarding.connectingSource = null
        onboarding.phase = SetupPhase.SOURCES
        authVM.reset()
        navController.navigate(Routes.SETUP) { launchSingleTop = true }
    }
    fun leaveOnboardingTask(done: Boolean?) {
        onboarding.activeTask?.let { task ->
            if (done == true) onboarding.completedTasks = onboarding.completedTasks + task
            if (done == false) onboarding.skippedTasks = onboarding.skippedTasks + task
        }
        onboarding.activeTask = null
        onboarding.phase = SetupPhase.TASKS
        navController.navigate(Routes.SETUP) { launchSingleTop = true }
    }
    val onTopLevel = currentRoute in topLevelDestinations.map { it.route }
    val showChrome = currentRoute != null && currentRoute != Routes.SIGN_IN && currentRoute != Routes.SETUP
    val windowLayout = LocalWindowLayout.current
    val rail = windowLayout.useNavigationRail && showChrome
    var sidePaneName by rememberSaveable { mutableStateOf<String?>(null) }
    val sidePane = sidePaneName?.let { runCatching { PlayerPane.valueOf(it) }.getOrNull() }
    val shell = windowLayout.shell(panelRequested = rail && sidePane != null && playerState.hasTrack)
    val uiPrefs = com.aurora.music.ui.theme.LocalUiPrefs.current
    val simpleMode by container.settingsStore.simpleMode.collectAsStateWithLifecycle(initialValue = false)
    val navLayout = remember(uiPrefs.navLayout, simpleMode) {
        com.aurora.music.navigation.NavMenu.parse(uiPrefs.navLayout).visible(simpleMode)
    }
    val navGap = if (rail) uiPrefs.tabletNavGap.dp else 0.dp
    val pageMargin = if (rail) uiPrefs.tabletPageMargin.dp else 0.dp
    val panelSpacing = uiPrefs.tabletPanelSpacing.dp.coerceAtLeast(4.dp)
    val density = androidx.compose.ui.platform.LocalDensity.current
    var dockHeight by remember { mutableStateOf(0.dp) }
    val dockVisible = showChrome && rail && playerState.hasTrack && !imeVisible
    val pageWidth = when {
        currentRoute == Routes.SIGN_IN || currentRoute == Routes.SETUP -> androidx.compose.ui.unit.Dp.Unspecified
        currentRoute in topLevelDestinations.map { it.route } -> 1280.dp
        currentRoute == Routes.SETTINGS && windowLayout.useNavigationRail -> 1280.dp
        currentRoute?.startsWith("settings") == true -> 840.dp
        else -> 960.dp
    }

    var showSpeedSheet by rememberSaveable { mutableStateOf(false) }
    var showQueue by rememberSaveable { mutableStateOf(false) }
    var showMix by remember { mutableStateOf(false) }
    var mixRequest by remember { mutableStateOf<com.aurora.music.mix.MixCollectionRequest?>(null) }
    var mixQueue by remember { mutableStateOf<List<com.aurora.music.model.Song>>(emptyList()) }
    var showVisualizer by rememberSaveable { mutableStateOf(false) }
    var showOutput by rememberSaveable { mutableStateOf(false) }
    var showSleep by rememberSaveable { mutableStateOf(false) }
    var playerPaneRequest by remember { mutableStateOf<PlayerPane?>(null) }
    fun showPane(pane: PlayerPane) {
        if (windowLayout.canShowSidePanel) {
            sidePaneName = if (sidePane == pane && shell.sidePanel) null else pane.name
        } else {
            playerPaneRequest = pane
            playerVM.setExpanded(true)
        }
    }

    val mixVM: com.aurora.music.viewmodel.MixViewModel = viewModel(key = "mix-${session?.server}-${session?.username}")
    val mixUi by mixVM.state.collectAsStateWithLifecycle()
    val mixPlayback by mixVM.playback.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(mixRequest?.token) {
        mixRequest?.let { request ->
            mixVM.mixCollection(request.kind, request.id, request.name, playInPlayer = true) {
                playerVM.setExpanded(true)
            }
            mixRequest = null
        }
    }
    androidx.compose.runtime.LaunchedEffect(mixUi.message, showMix) {
        if (!showMix) mixUi.message?.let { confirm(it); mixVM.dismissMessage() }
    }
    androidx.compose.runtime.LaunchedEffect(mixPlayback.error) {
        if (!showMix) mixPlayback.error?.let { confirm(it) }
    }

    val playerColors = rememberPlayerColorScheme(playerState.current.artworkUrl, playerState.current.accent)

    fun navigateTopLevel(route: String) {
        if (navController.currentDestination?.route == route) return
        container.haptic()
        // tab press lands on the tab root pop pushed detail/settings dont restore the sub-stack
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = false }
            launchSingleTop = true
            restoreState = false
        }
    }

    fun openNavItem(item: com.aurora.music.navigation.NavMenuItem) {
        if (item.topLevel) navigateTopLevel(item.route)
        else if (navController.currentDestination?.route != item.route) navController.navigate(item.route) { launchSingleTop = true }
    }

    fun openDrawer() {
        if (rail && shell.expandedSidebar) {
            navController.navigate(Routes.PROFILE) { launchSingleTop = true }
            return
        }
        if (!drawerNavigationPending && !drawerState.isAnimationRunning) {
            scope.launch { drawerState.open() }
        }
    }
    fun closeDrawerThen(action: () -> Unit) {
        if (drawerNavigationPending) return
        drawerNavigationPending = true
        scope.launch {
            try {
                // Finish dismissing before changing routes. Otherwise the scrim can
                // outlive its screen and intercept taps over a blank destination.
                drawerState.close()
                action()
            } finally {
                drawerNavigationPending = false
            }
        }
    }
    fun openDetail(kind: String, id: String) = navController.navigate(Routes.detail(kind, id))
    fun playAlbum(id: String) = scope.launch {
        container.repository.detail("album", id)?.let { if (it.tracks.isNotEmpty()) playerVM.playCollection("album", id, it.tracks, 0, it.info.songCount) }
    }
    fun playById(id: String) = scope.launch {
        container.repository.songFor(id)?.let { playerVM.play(it) }
    }
    fun logout() {
        // dont stop playback here the account-change observer saves the queue first then stops
        scope.launch { container.signOut() }
        navController.navigate(Routes.SIGN_IN) { popUpTo(0) }
    }

    // wait for the persisted session check before choosing a start destination
    if (sessionReady == null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            com.aurora.music.ui.components.LottieLoader(modifier = Modifier.size(90.dp))
        }
        return
    }
    val startDestination = rememberSaveable {
        if (onboarding.phase in setOf(SetupPhase.QUESTIONS, SetupPhase.SOURCES, SetupPhase.TASKS))
            Routes.SETUP else if (sessionReady == true) Routes.HOME else Routes.SIGN_IN
    }

    // session ready while still on sign-in eg async spotify oauth redirect advance to home
    androidx.compose.runtime.LaunchedEffect(sessionReady) {
        if (sessionReady == true && onboarding.phase != SetupPhase.AUTH &&
            navController.currentDestination?.route == Routes.SIGN_IN) {
            navController.navigate(Routes.HOME) { popUpTo(Routes.SIGN_IN) { inclusive = true } }
        }
    }

    // adding an account reuses sign-in while logged in so sessionReady doesnt change account epoch advances to home
    // guard on sessionReady true so logout which also bumps the epoch doesnt bounce off sign-in
    val accountEpoch by container.accountEpoch.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(accountEpoch) {
        if (accountEpoch > 0 && sessionReady == true && onboarding.phase != SetupPhase.AUTH &&
            navController.currentDestination?.route == Routes.SIGN_IN) {
            navController.navigate(Routes.HOME) { popUpTo(Routes.SIGN_IN) { inclusive = true } }
        }
    }

    // backfill avatar for sessions created before we stored it
    androidx.compose.runtime.LaunchedEffect(sessionReady, session?.imageUrl) {
        if (sessionReady == true && session != null && session?.imageUrl.isNullOrBlank()) {
            val url = runCatching { container.repository.profileImageUrl() }.getOrNull()
            if (!url.isNullOrBlank()) container.settingsStore.updateUserImage(url)
        }
    }

    val setupUi: @Composable () -> Unit = {
        AuroraOnboarding(
            state = onboarding,
            connected = savedSessions.map { it.type }.toSet(),
            hasSession = sessionReady == true,
            onConnectSource = { source ->
                onboarding.connectingSource = source
                onboarding.phase = SetupPhase.AUTH
                authVM.reset()
                if (source != SetupSource.LOCAL) authVM.selectType(source.type)
                navController.navigate(Routes.SIGN_IN) { launchSingleTop = true }
            },
            onLeaveAuth = ::leaveOnboardingAuth,
            onOpenTask = { task ->
                onboarding.activeTask = task
                onboarding.phase = SetupPhase.TASK
                navController.navigate(task.route) { launchSingleTop = true }
            },
            onLeaveTask = ::leaveOnboardingTask,
            onFinish = ::finishOnboarding,
            onChoicesConfirmed = {
                scope.launch { container.settingsStore.setSimpleMode(onboarding.mode == ListeningMode.EVERYDAY) }
            },
        )
    }

    val nowPlayingPane: @Composable (PlayerPane, Modifier) -> Unit = { pane, modifier ->
        when (pane) {
            PlayerPane.LYRICS -> LyricsPane(playerState, onSeek = { playerVM.seekTo(it) }, modifier = modifier.clip(RoundedCornerShape(20.dp)))
            PlayerPane.QUEUE -> QueueContent(
                queue = playerState.queue,
                currentIndex = playerState.currentIndex,
                isPlaying = playerState.isPlaying,
                onJump = { playerVM.jumpTo(it) },
                onRemove = { playerVM.removeFromQueue(it) },
                onMove = { from, to -> playerVM.moveQueueItem(from, to) },
                onClear = { playerVM.clearQueue() },
                onSaveAsPlaylist = { name -> playerVM.saveQueueAsPlaylist(name) { confirm(it) } },
                onOpenMix = { playerVM.setExpanded(false); mixQueue = playerState.queue.filterNot { it.id.startsWith("aurora-mix:") }; showMix = true },
                editable = !playerState.isMix,
                showTitle = false,
                showActions = false,
                modifier = modifier.padding(horizontal = 4.dp),
            )
        }
    }
    val nowPlayingPaneActions: @Composable (PlayerPane) -> Unit = { pane ->
        if (pane == PlayerPane.QUEUE) com.aurora.music.ui.screens.player.QueueActions(
            queue = playerState.queue,
            currentIndex = playerState.currentIndex,
            editable = !playerState.isMix,
            onClear = { playerVM.clearQueue() },
            onSaveAsPlaylist = { name -> playerVM.saveQueueAsPlaylist(name) { confirm(it) } },
        )
    }

    val detailContent: @Composable (String, String, DetailViewModel, androidx.compose.foundation.layout.PaddingValues, () -> Unit, (String, String) -> Unit) -> Unit =
        { kind, id, detailVM, padding, onBack, onOpen ->
            androidx.compose.runtime.LaunchedEffect(kind, id) { detailVM.load(kind, id) }
            val detailState by detailVM.state.collectAsStateWithLifecycle()
            // resolve liked state for visible tracks so hearts show beyond page 1
            androidx.compose.runtime.LaunchedEffect(detailState.data?.tracks?.size) {
                detailState.data?.tracks?.let { ts -> playerVM.checkLiked(ts.map { it.id }) }
            }
            DetailScreen(
                contentPadding = padding,
                state = detailState,
                likedIds = playerState.likedIds,
                currentSongId = playerState.current.id,
                isPlaying = playerState.isPlaying,
                onBack = onBack,
                onPlayAll = { songs, index -> playerVM.playCollection(kind, id, songs, index, detailState.data?.info?.songCount ?: songs.size) },
                onShufflePlay = { songs -> playerVM.shuffleCollection(kind, id, songs, detailState.data?.info?.songCount ?: songs.size) },
                onMix = {
                    playerVM.setExpanded(false)
                    mixRequest = com.aurora.music.mix.MixCollectionRequest(kind, id, detailState.data?.info?.title ?: appString(R.string.text_collection_30c54a))
                    showMix = false
                    confirm(appString(R.string.text_preparing_mix_83b489))
                },
                onAddToQueue = { playerVM.addToQueue(it); confirm(appString(R.string.text_added_to_queue_9d7749)) },
                onPlayNext = { playerVM.playNext(it); confirm(appString(R.string.text_playing_next_b23445)) },
                onToggleLike = { playerVM.toggleLike(it) },
                onOpenDetail = onOpen,
                itemKind = kind,
                isItemLiked = playerState.likedIds.contains(id),
                onToggleItemLike = { playerVM.toggleLike(id, kind) },
                downloadedIds = downloadedIds,
                onDownload = onDownload,
                onRemoveDownload = onRemoveDownload,
                onDownloadAll = {
                    val d = detailState.data
                    if (d != null) {
                        val started = if (kind == "album" || kind == "playlist") {
                            container.downloadManager.downloadCollection(id, kind, d.info.title, d.info.subtitle, d.info.artUrl, d.tracks)
                        } else {
                            container.downloadManager.downloadAll(d.tracks)
                        }
                        val n = d.tracks.count { !container.repository.isDownloaded(it.id) }
                        confirm(if (!started) appString(R.string.download_start_failed)
                            else if (n > 0) appString(R.string.downloading_tracks, appPlural(R.plurals.track_count, n)) else appString(R.string.text_already_downloaded_8acc8a))
                    }
                },
                onRemoveDownloads = {
                    if (kind == "album" || kind == "playlist") container.repository.removeDownloadedCollection(id, kind)
                    detailState.data?.tracks?.forEach { container.repository.removeDownload(it.id) }
                },
                onEditPlaylist = { name, desc ->
                    playlistMutation { container.repository.updatePlaylist(id, name, desc) }.also { updated ->
                        if (updated) detailVM.reload(kind, id)
                    }
                },
                onDeletePlaylist = {
                    scope.launch {
                        if (playlistMutation { container.repository.deletePlaylist(id) }) onBack()
                        else confirm(appString(R.string.playlist_delete_failed))
                    }
                },
                onLoadMore = { detailVM.loadMore() },
                canDownload = !localMode,
                isPinned = pins.any { it.id == id && it.kind == kind },
                onTogglePin = {
                    val d = detailState.data
                    val pin = com.aurora.music.data.Pin(
                        id = id, kind = kind,
                        title = d?.info?.title ?: kind,
                        subtitle = d?.info?.subtitle ?: "",
                        coverUrl = d?.info?.artUrl ?: "",
                        serverId = currentServer,
                    )
                    scope.launch { container.settingsStore.togglePin(pin) }
                },
                onEditTags = { song -> navController.navigate(Routes.tagEdit(song.id)) },
                serverTagEditing = serverTagEditing,
                artistInfo = detailState.artistInfo,
            )
        }

    val settingsGraph: androidx.navigation.NavGraphBuilder.(androidx.navigation.NavHostController, androidx.compose.foundation.layout.PaddingValues) -> Unit =
        { nav, padding ->
                composable(Routes.SETTINGS_ADVANCED_AUDIO) {
                    com.aurora.music.ui.screens.settings.AdvancedAudioSettingsScreen(
                        contentPadding = padding,
                        onBack = { nav.popBackStack() },
                        onOpen = { destination -> nav.navigate(destination.route) },
                    )
                }
                composable(Routes.SETTINGS_LISTENING) {
                    com.aurora.music.ui.screens.settings.ListeningLevelsScreen(contentPadding = padding, onBack = { nav.popBackStack() })
                }
                composable(Routes.SETTINGS_EXTENSIONS) {
                    com.aurora.music.ui.screens.settings.ExtensionsScreen(contentPadding = padding, onBack = { nav.popBackStack() },
                        onRack = { nav.navigate(Routes.SETTINGS_PROCESSING_RACK) })
                }
                composable(Routes.SETTINGS_ACCOUNTS) {
                    com.aurora.music.ui.screens.settings.AccountsScreen(
                        contentPadding = padding,
                        onBack = { nav.popBackStack() },
                        onSwitch = { s ->
                            scope.launch { container.switchSession(s) }   // playback stop and reload via accountEpoch
                            confirm(appString(R.string.text_switched_to_c57200, (s.typeLabel.localizedMediaType())))
                            nav.popBackStack()
                        },
                        onForget = { s -> scope.launch { container.forgetSavedSession(s) } },
                        onAddAccount = { authVM.reset(); navController.navigate(Routes.SIGN_IN) },
                    )
                }
                composable(Routes.SETTINGS_PLAYBACK) {
                    PlaybackSettingsScreen(
                        contentPadding = padding, onBack = { nav.popBackStack() },
                        onOpenOutput = { nav.navigate(Routes.SETTINGS_OUTPUT) },
                        onOpenLoudness = { nav.navigate(Routes.SETTINGS_LOUDNESS) },
                        onOpenEq = { nav.navigate(Routes.SETTINGS_EQ) },
                    )
                }
                composable(Routes.SETTINGS_ALARM) {
                    com.aurora.music.ui.screens.settings.AlarmSettingsScreen(
                        contentPadding = padding, onBack = { nav.popBackStack() },
                        onOpenPermissions = { nav.navigate(Routes.SETTINGS_PERMISSIONS) },
                    )
                }
                composable(Routes.SIGNAL_PATH) {
                    com.aurora.music.ui.screens.settings.SignalPathScreen(
                        contentPadding = padding, onBack = { nav.popBackStack() },
                        onOpenOutput = { nav.navigate(Routes.SETTINGS_OUTPUT) },
                    )
                }
                composable(Routes.SETTINGS_NETWORK) {
                    com.aurora.music.ui.screens.settings.NetworkOutputScreen(padding) { nav.popBackStack() }
                }
                composable(Routes.SETTINGS_OUTPUT) {
                    com.aurora.music.ui.screens.settings.AudioOutputSettingsScreen(
                        contentPadding = padding, onBack = { nav.popBackStack() },
                        onOpenSignalPath = { nav.navigate(Routes.SIGNAL_PATH) },
                    )
                }
                composable(Routes.SETTINGS_LOUDNESS) {
                    com.aurora.music.ui.screens.settings.LoudnessSettingsScreen(
                        contentPadding = padding, onBack = { nav.popBackStack() },
                        onOpenEq = { nav.navigate(Routes.SETTINGS_EQ) },
                        onOpenSignalPath = { nav.navigate(Routes.SIGNAL_PATH) },
                    )
                }
                composable(Routes.SETTINGS_BACKUP) {
                    com.aurora.music.ui.screens.settings.BackupScreen(
                        contentPadding = padding,
                        onBack = { nav.popBackStack() },
                        confirm = { confirm(it) },
                    )
                }
                composable(Routes.SETTINGS_GESTURES) {
                    com.aurora.music.ui.screens.settings.GesturesSettingsScreen(contentPadding = padding, onBack = { nav.popBackStack() })
                }
                composable(Routes.SETTINGS_INTEGRATIONS) {
                    com.aurora.music.ui.screens.settings.IntegrationsSettingsScreen(
                        contentPadding = padding,
                        onBack = { nav.popBackStack() },
                        onOpenLyrics = { nav.navigate(Routes.SETTINGS_INTEGRATION_LYRICS) },
                        onOpenLastfm = { nav.navigate(Routes.SETTINGS_INTEGRATION_LASTFM) },
                        onOpenListenBrainz = { nav.navigate(Routes.SETTINGS_INTEGRATION_LISTENBRAINZ) },
                        onOpenDiscord = { nav.navigate(Routes.SETTINGS_INTEGRATION_DISCORD) },
                        onOpenArtistInfo = { nav.navigate(Routes.SETTINGS_INTEGRATION_ARTIST_INFO) },
                        onOpenAcoustId = { nav.navigate(Routes.SETTINGS_INTEGRATION_ACOUSTID) },
                    )
                }
                composable(Routes.SETTINGS_INTEGRATION_LYRICS) {
                    com.aurora.music.ui.screens.settings.LyricsIntegrationScreen(padding) { nav.popBackStack() }
                }
                composable(Routes.SETTINGS_INTEGRATION_LASTFM) {
                    com.aurora.music.ui.screens.settings.LastfmIntegrationScreen(padding) { nav.popBackStack() }
                }
                composable(Routes.SETTINGS_INTEGRATION_LISTENBRAINZ) {
                    com.aurora.music.ui.screens.settings.ListenBrainzIntegrationScreen(padding) { nav.popBackStack() }
                }
                composable(Routes.SETTINGS_INTEGRATION_DISCORD) {
                    com.aurora.music.ui.screens.settings.DiscordIntegrationScreen(
                        contentPadding = padding,
                        onBack = { nav.popBackStack() },
                        onOpenDiscordLogin = { nav.navigate(Routes.DISCORD_LOGIN) },
                    )
                }
                composable(Routes.SETTINGS_INTEGRATION_ARTIST_INFO) {
                    com.aurora.music.ui.screens.settings.ArtistInfoIntegrationScreen(padding) { nav.popBackStack() }
                }
                composable(Routes.SETTINGS_INTEGRATION_ACOUSTID) {
                    com.aurora.music.ui.screens.settings.AcoustIdIntegrationScreen(padding) { nav.popBackStack() }
                }
                composable(Routes.SETTINGS_APPEARANCE) {
                    com.aurora.music.ui.screens.settings.AppearanceScreen(contentPadding = padding, onBack = { nav.popBackStack() },
                        onOpenNavigationMenu = { nav.navigate(Routes.SETTINGS_NAV_MENU) })
                }
                composable(Routes.SETTINGS_NAV_MENU) {
                    com.aurora.music.ui.screens.settings.NavigationMenuScreen(contentPadding = padding, onBack = { nav.popBackStack() })
                }
                composable(Routes.DISCORD_LOGIN) {
                    com.aurora.music.ui.screens.settings.DiscordLoginScreen(
                        contentPadding = padding,
                        onBack = { nav.popBackStack() },
                        onToken = { token ->
                            scope.launch { container.settingsStore.saveDiscord(token, "") }
                            nav.popBackStack()
                        },
                    )
                }
                composable(Routes.SETTINGS_EQ) {
                    com.aurora.music.ui.screens.settings.EqualizerScreen(
                        contentPadding = padding, onBack = { nav.popBackStack() },
                        onOpenLoudness = { nav.navigate(Routes.SETTINGS_LOUDNESS) },
                        onOpenProcessingPresets = { nav.navigate(Routes.SETTINGS_PROCESSING_PRESETS) },
                        onOpenProcessingRack = { nav.navigate(Routes.SETTINGS_PROCESSING_RACK) },
                        onOpenImpulses = { nav.navigate(Routes.SETTINGS_IMPULSES) },
                    )
                }
                composable(Routes.SETTINGS_PROCESSING_PRESETS) {
                    com.aurora.music.ui.screens.settings.ProcessingPresetsScreen(
                        contentPadding = padding, onBack = { nav.popBackStack() },
                        onOpenSignalPath = { nav.navigate(Routes.SIGNAL_PATH) },
                    )
                }
                composable(Routes.SETTINGS_PROCESSING_RACK) {
                    com.aurora.music.ui.screens.settings.ProcessingRackScreen(
                        contentPadding = padding, onBack = { nav.popBackStack() },
                        onOpenPresets = { nav.navigate(Routes.SETTINGS_PROCESSING_PRESETS) },
                        onOpenSignalPath = { nav.navigate(Routes.SIGNAL_PATH) },
                        onOpenTuning = { nav.navigate(Routes.SETTINGS_TUNING) },
                        onOpenImpulses = { nav.navigate(Routes.SETTINGS_IMPULSES) },
                    )
                }
                composable(Routes.SETTINGS_TUNING) {
                    com.aurora.music.ui.screens.settings.TuningProjectsScreen(
                        contentPadding = padding, onBack = { nav.popBackStack() },
                        onOpenRack = { nav.navigate(Routes.SETTINGS_PROCESSING_RACK) },
                    )
                }
                composable(Routes.SETTINGS_IMPULSES) {
                    com.aurora.music.ui.screens.settings.ConvolutionLibraryScreen(
                        contentPadding = padding, onBack = { nav.popBackStack() },
                    )
                }
                composable(Routes.SETTINGS_COMPARISON) {
                    com.aurora.music.ui.screens.settings.ComparisonScreen(
                        contentPadding = padding, onBack = { nav.popBackStack() },
                        onOpenPresets = { nav.navigate(Routes.SETTINGS_PROCESSING_PRESETS) },
                        currentSource = playerState.current.streamUrl, currentTitle = playerState.current.title,
                    )
                }
                composable(Routes.SETTINGS_PRESET_RULES) {
                    com.aurora.music.ui.screens.settings.PresetRulesScreen(padding) { nav.popBackStack() }
                }
                composable(Routes.SETTINGS_VISUALIZER) {
                    com.aurora.music.ui.screens.settings.VisualizerSettingsScreen(contentPadding = padding, onBack = { nav.popBackStack() })
                }
                composable(Routes.SETTINGS_SONIC) {
                    com.aurora.music.ui.screens.settings.SonicSettingsScreen(contentPadding = padding, onBack = { nav.popBackStack() })
                }
                composable(Routes.SETTINGS_SOURCES) {
                    com.aurora.music.ui.screens.settings.SourcesSettingsScreen(contentPadding = padding, onBack = { nav.popBackStack() },
                        onArtistSeparators = { nav.navigate(Routes.SETTINGS_ARTIST_SEPARATORS) })
                }
                composable(Routes.SETTINGS_ARTIST_SEPARATORS) {
                    com.aurora.music.ui.screens.settings.ArtistSeparatorsScreen(contentPadding = padding, onBack = { nav.popBackStack() })
                }
                composable(Routes.SETTINGS_PERMISSIONS) {
                    com.aurora.music.ui.screens.settings.PermissionsScreen(contentPadding = padding, onBack = { nav.popBackStack() })
                }
                composable(Routes.SETTINGS_STORAGE) {
                    com.aurora.music.ui.screens.settings.StorageSettingsScreen(contentPadding = padding, onBack = { nav.popBackStack() })
                }
                composable(Routes.SETTINGS_ABOUT) {
                    com.aurora.music.ui.screens.settings.AboutSettingsScreen(contentPadding = padding, onBack = { nav.popBackStack() })
                }
                composable(Routes.SETTINGS_LANGUAGE) {
                    com.aurora.music.ui.screens.settings.LanguageSettingsScreen(contentPadding = padding, onBack = { nav.popBackStack() })
                }
        }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen || drawerState.targetValue == DrawerValue.Open ||
            (showChrome && onTopLevel && !(rail && shell.expandedSidebar)),
        drawerContent = {
            ModalDrawerSheet(drawerState = drawerState, drawerContainerColor = MaterialTheme.colorScheme.surface) {
                SidebarContent(
                    username = profileAppearance.name,
                    server = session?.server ?: "",
                    avatarUrl = profileAppearance.avatarUrl,
                    layout = navLayout,
                    currentRoute = currentRoute,
                    onProfile = { closeDrawerThen { navController.navigate(Routes.PROFILE) } },
                    onSettings = { closeDrawerThen { navController.navigate(Routes.SETTINGS) } },
                    onOpen = { item -> closeDrawerThen { openNavItem(item) } },
                    onLogout = { closeDrawerThen { logout() } },
                )
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            AmbientBackground()
            if (rail) {
                Box(
                    Modifier.fillMaxHeight().then(
                        if (dockVisible) Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(androidx.compose.foundation.layout.WindowInsetsSides.Bottom))
                            .padding(bottom = dockHeight + 12.dp) else Modifier
                    ),
                ) {
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
                    )
                } else {
                    TabletNavigationRail(currentRoute, navLayout.main, { openNavItem(it) }, { openDrawer() }) {
                        navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                    }
                }
                }
            }
            Scaffold(
                modifier = Modifier.padding(start = if (rail) shell.navWidth + navGap else 0.dp).then(
                    if (dockVisible) Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(androidx.compose.foundation.layout.WindowInsetsSides.Bottom))
                        .padding(bottom = dockHeight + 12.dp + panelSpacing) else Modifier
                ),
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    if (showChrome) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(
                            Modifier
                                .then(if (rail) Modifier.fillMaxWidth() else Modifier.widthIn(max = 960.dp))
                                .windowInsetsPadding(WindowInsets.navigationBars)
                                .padding(start = if (rail) pageMargin else 10.dp, end = if (rail) pageMargin.coerceAtLeast(12.dp) else 10.dp,
                                    top = 8.dp, bottom = if (dockVisible) 0.dp else if (rail) 12.dp else 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (activeDownloads > 0) {
                                DownloadProgressBanner(count = activeDownloads, progress = downloadProgress)
                                Spacer(Modifier.height(8.dp))
                            }
                            if (offlineMode) {
                                Row(
                                    Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)).padding(horizontal = 14.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Filled.CloudOff, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(appString(R.string.text_offline_mode_66cf31), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                            if (playerState.hasTrack && !rail) {
                                // The mini player belongs to the app chrome, so it uses the app palette.
                                MiniPlayer(
                                    state = playerState,
                                    onExpand = { if (playerState.current.id.startsWith("aurora-mix:")) showMix = true else playerVM.setExpanded(true) },
                                    onTogglePlay = { playerVM.togglePlay() },
                                    onToggleLike = { playerVM.toggleLikeCurrent() },
                                    onNext = { playerVM.next() },
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            if (!rail) FloatingNav(currentRoute) { navigateTopLevel(it) }
                        }
                        }
                    }
                },
            ) { inner ->
                Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = pageMargin), contentAlignment = Alignment.TopCenter) {
                NavHost(
                    navController = navController,
                    startDestination = startDestination,
                    modifier = Modifier.widthIn(max = pageWidth).fillMaxSize(),
                    // A drawer action followed quickly by Back interrupts Navigation
                    // 2.8's default 700 ms fade and can leave the returned page invisible.
                    // The drawer and player animate independently; route content stays visible.
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None },
                ) {
                    composable(Routes.SETUP) { setupUi() }
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
                                    navController.navigate(Routes.HOME) {
                                        popUpTo(Routes.SIGN_IN) { inclusive = true }
                                    }
                                }
                            },
                            onAuthUrlOpened = authVM::authUrlOpened,
                            onLocal = authVM::signInLocal,
                            onConnectSpotify = authVM::connectSpotify,
                            onConnectYouTubeMusic = authVM::connectYouTubeMusic,
                            savedSessions = savedSessions,
                            onUseSaved = { s -> authVM.useSaved(s) },   // nav to home handled by the sessionReady effect
                        )
                    }
                    composable(Routes.NOTIFICATIONS) {
                        com.aurora.music.ui.screens.stats.RecapInboxScreen(inner, { navController.popBackStack() },
                            { navController.navigate("recap/${it.period.name}/${it.start}") })
                    }
                    composable("recap/{period}/{date}") { entry ->
                        val period = runCatching { com.aurora.music.data.RecapPeriod.valueOf(entry.arguments?.getString("period").orEmpty()) }.getOrDefault(com.aurora.music.data.RecapPeriod.DAY)
                        val date = runCatching { java.time.LocalDate.parse(entry.arguments?.getString("date")) }.getOrDefault(java.time.LocalDate.now().minusDays(1))
                        com.aurora.music.ui.screens.stats.ListeningStatsScreen(inner, { navController.popBackStack() }, { playById(it) }, { k, i -> openDetail(k, i) }, com.aurora.music.data.RecapWindow.containing(period, date))
                    }
                    composable(Routes.HOME) {
                        val homeVM: HomeViewModel = viewModel()
                        val homeState by homeVM.state.collectAsStateWithLifecycle()
                        HomeScreen(
                            contentPadding = inner,
                            state = homeState,
                            username = profileAppearance.name,
                            avatarUrl = profileAppearance.avatarUrl,
                            onOpenDrawer = { openDrawer() },
                            onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                            onSettingsBounds = {},
                            onOpenDetail = { kind, id -> openDetail(kind, id) },
                            onPlayAlbum = { playAlbum(it) },
                            onPlayAll = { songs, index -> playerVM.playAll(songs, index) },
                            onLoadMore = homeVM::loadMore,
                            onSelectFeed = homeVM::selectFeed,
                            onOpenNotifications = { navController.navigate(Routes.NOTIFICATIONS) },
                            onRetry = { homeVM.load() },
                            onAddSource = { authVM.reset(); navController.navigate(Routes.SIGN_IN) },
                        )
                    }
                    composable(Routes.SEARCH) {
                        val searchVM: SearchViewModel = viewModel(viewModelStoreOwner = activityOwner)
                        val searchState by searchVM.state.collectAsStateWithLifecycle()
                        val recentSearches by searchVM.recentSearches.collectAsStateWithLifecycle()
                        androidx.compose.runtime.LaunchedEffect(searchState.results.songs.size) {
                            playerVM.checkLiked(searchState.results.songs.map { it.id })
                        }
                        SearchScreen(
                            contentPadding = inner,
                            state = searchState,
                            likedIds = playerState.likedIds,
                            currentSongId = playerState.current.id,
                            isPlaying = playerState.isPlaying,
                            onQuery = searchVM::onQuery,
                            onSelectSource = searchVM::selectSource,
                            onPlayAll = { songs, index -> playerVM.playAll(songs, index) },
                            onAddToQueue = { playerVM.addToQueue(it); confirm(appString(R.string.text_added_to_queue_9d7749)) },
                            onPlayNext = { playerVM.playNext(it); confirm(appString(R.string.text_playing_next_b23445)) },
                            onToggleLike = { playerVM.toggleLike(it) },
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
                        val libraryVM: LibraryViewModel = viewModel()
                        val libraryState by libraryVM.state.collectAsStateWithLifecycle()
                        // m3u export staged here written once the user picks a file
                        var pendingM3u by remember { mutableStateOf<String?>(null) }
                        val exportM3uLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
                            val text = pendingM3u
                            pendingM3u = null
                            if (uri != null && text != null) {
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    val ok = runCatching {
                                        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } != null
                                    }.getOrDefault(false)
                                    confirm(if (ok) appString(R.string.text_playlist_exported_0efbda) else appString(R.string.text_export_failed_d6c17e))
                                }
                            }
                        }
                        val importM3uLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                            if (uri != null) scope.launch {
                                val (text, displayName) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    val t = runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
                                    // saf uris carry opaque doc ids the human file name needs a query
                                    val n = runCatching {
                                        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                                            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                                        }
                                    }.getOrNull()
                                    t to n
                                }
                                val entries = text?.let { com.aurora.music.data.M3u.parse(it) }.orEmpty()
                                if (entries.isEmpty()) { confirm(appString(R.string.text_no_tracks_found_in_that_file_754434)) } else {
                                    val name = displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: appString(R.string.text_imported_playlist_37a186)
                                    confirm(appString(R.string.text_importing_tracks_3abc1a, (entries.size)))
                                    val result = container.repository.importPlaylist(name, entries)
                                    if (result == null) confirm(appString(R.string.text_import_failed_fbae89)) else {
                                        confirm(appString(R.string.text_matched_of_tracks_09b8b1, (result.first), (result.second)))
                                        libraryVM.load()
                                    }
                                }
                            }
                        }
                        var librarySelection by rememberSaveable { mutableStateOf<String?>(null) }
                        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                        val split = rail && maxWidth >= 960.dp
                        BackHandler(enabled = split && librarySelection != null) { librarySelection = null }
                        Row(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                            LibraryScreen(
                                contentPadding = inner,
                                state = libraryState,
                                onRefresh = libraryVM::refresh,
                                username = profileAppearance.name,
                                likedIds = playerState.likedIds,
                                currentSongId = playerState.current.id,
                                isPlaying = playerState.isPlaying,
                                onFilter = libraryVM::setFilter,
                                onSort = libraryVM::setSort,
                                onToggleLayout = libraryVM::toggleLayout,
                                onOpenDrawer = { openDrawer() },
                                onPlayAll = { songs, index -> playerVM.playAll(songs, index) },
                                onAddToQueue = { playerVM.addToQueue(it); confirm(appString(R.string.text_added_to_queue_9d7749)) },
                                onPlayNext = { playerVM.playNext(it); confirm(appString(R.string.text_playing_next_b23445)) },
                                onToggleLike = { playerVM.toggleLike(it) },
                                onOpenDetail = { kind, id -> if (split && kind in listOf("album", "artist", "playlist", "liked", "smart")) librarySelection = "$kind:$id" else openDetail(kind, id) },
                                downloadedIds = downloadedIds,
                                onDownload = onDownload,
                                onRemoveDownload = onRemoveDownload,
                                onOpenSearch = { navigateTopLevel(Routes.SEARCH) },
                                onOpenMix = { playerVM.setExpanded(false); mixQueue = playerState.queue.filterNot { it.id.startsWith("aurora-mix:") }; showMix = true },
                                onCreatePlaylist = { name ->
                                    playlistMutation { container.repository.createPlaylist(name) }.also { created ->
                                        if (created) libraryVM.load()
                                    }
                                },
                                onCreateSmart = { navController.navigate(Routes.smartEdit()) },
                                onEditSmart = { id -> navController.navigate(Routes.smartEdit(id)) },
                                onDeleteSmart = { id -> scope.launch { container.settingsStore.deleteSmartPlaylist(id) } },
                                onImportM3u = { importM3uLauncher.launch(arrayOf("*/*")) },
                                onExportPlaylist = { id, kind, title -> scope.launch {
                                    val text = container.repository.exportPlaylist(kind, id)
                                    if (text == null) confirm(appString(R.string.text_nothing_to_export_7faf33)) else {
                                        pendingM3u = text
                                        exportM3uLauncher.launch("$title.m3u8")
                                    }
                                } },
                                onOpenFolders = { navController.navigate(Routes.folders()) },
                                onOpenRadio = { navController.navigate(Routes.RADIO) },
                                onOpenPodcasts = { navController.navigate(Routes.PODCASTS) },
                                onPlayCollection = { id, kind -> scope.launch { container.repository.detail(kind, id)?.let { d -> if (d.tracks.isNotEmpty()) playerVM.playCollection(kind, id, d.tracks, 0, d.info.songCount) } } },
                                onShuffleCollection = { id, kind -> scope.launch { container.repository.detail(kind, id)?.let { d -> if (d.tracks.isNotEmpty()) playerVM.shuffleCollection(kind, id, d.tracks, d.info.songCount) } } },
                                onQueueCollection = { id, kind -> scope.launch {
                                    val tracks = container.repository.detail(kind, id)?.tracks.orEmpty()
                                    tracks.forEach { playerVM.addToQueue(it) }
                                    if (tracks.isNotEmpty()) confirm(appString(R.string.text_added_to_queue_88e96c, (tracks.size)))
                                } },
                                onToggleLikeKind = { id, kind -> playerVM.toggleLike(id, kind) },
                                onDeletePlaylist = { id -> scope.launch {
                                    if (playlistMutation { container.repository.deletePlaylist(id) }) libraryVM.load()
                                    else confirm(appString(R.string.playlist_delete_failed))
                                } },
                                canDownload = !localMode,
                                pins = pins,
                                onEditTags = { song -> navController.navigate(Routes.tagEdit(song.id)) },
                                serverTagEditing = serverTagEditing,
                                onLoadMoreSongs = libraryVM::loadMoreSongs,
                                onPlayAllSongs = { shuffle ->
                                    scope.launch {
                                        val all = libraryVM.fullSortedSongs()
                                        if (all.isEmpty()) return@launch
                                        if (shuffle) playerVM.shufflePlay(all) else playerVM.playAll(all, 0)
                                    }
                                },
                                onPlaySong = { song ->
                                    scope.launch {
                                        val all = libraryVM.fullSortedSongs()
                                        val idx = all.indexOfFirst { it.id == song.id }
                                        // fall back to the single track if the full list somehow lacks it (never play the wrong index)
                                        if (idx >= 0) playerVM.playAll(all, idx) else playerVM.playAll(listOf(song), 0)
                                    }
                                },
                                selectedItem = if (split) librarySelection else null,
                            )
                            }
                            val selected = librarySelection?.split(":", limit = 2)
                            if (split && selected != null && selected.size == 2) {
                                Spacer(Modifier.width(16.dp))
                                Box(
                                    Modifier.weight(1.1f).fillMaxHeight().padding(top = 8.dp)
                                        .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)),
                                ) {
                                    val paneVM: DetailViewModel = viewModel(key = "library-detail")
                                    detailContent(selected[0], selected[1], paneVM, inner, { librarySelection = null }) { k, i ->
                                        if (k in listOf("album", "artist", "playlist")) librarySelection = "$k:$i" else openDetail(k, i)
                                    }
                                }
                            }
                        }
                        }
                    }
                    composable(
                        Routes.FOLDERS,
                        arguments = listOf(
                            androidx.navigation.navArgument("fid") { defaultValue = "" },
                            androidx.navigation.navArgument("title") { defaultValue = "" },
                        ),
                    ) { entry ->
                        val fid = entry.arguments?.getString("fid").orEmpty()
                        val folderTitle = entry.arguments?.getString("title").orEmpty()
                        val folderVM: com.aurora.music.viewmodel.FolderViewModel = viewModel()
                        androidx.compose.runtime.LaunchedEffect(fid) { folderVM.load(fid) }
                        val folderState by folderVM.state.collectAsStateWithLifecycle()
                        androidx.compose.runtime.LaunchedEffect(folderState.content?.songs?.size) {
                            folderState.content?.songs?.let { ss -> playerVM.checkLiked(ss.map { it.id }) }
                        }
                        com.aurora.music.ui.screens.library.FolderScreen(
                            contentPadding = inner,
                            title = folderTitle,
                            loading = folderState.loading,
                            content = folderState.content,
                            refreshing = folderState.refreshing,
                            onRefresh = folderVM::refresh,
                            likedIds = playerState.likedIds,
                            currentSongId = playerState.current.id,
                            isPlaying = playerState.isPlaying,
                            onBack = { navController.popBackStack() },
                            onOpenFolder = { id, name -> navController.navigate(Routes.folders(id, name)) },
                            onPlayAll = { songs, index -> playerVM.playAll(songs, index) },
                            onShufflePlay = { songs -> playerVM.shufflePlay(songs) },
                            onAddToQueue = { playerVM.addToQueue(it); confirm(appString(R.string.text_added_to_queue_9d7749)) },
                            onPlayNext = { playerVM.playNext(it); confirm(appString(R.string.text_playing_next_b23445)) },
                            onToggleLike = { playerVM.toggleLike(it) },
                            onOpenDetail = { k, i -> openDetail(k, i) },
                            downloadedIds = downloadedIds,
                            onDownload = onDownload,
                            onRemoveDownload = onRemoveDownload,
                            canDownload = !localMode,
                            onEditTags = { song -> navController.navigate(Routes.tagEdit(song.id)) },
                            serverTagEditing = serverTagEditing,
                        )
                    }
                    composable(
                        Routes.SMART_EDIT,
                        arguments = listOf(androidx.navigation.navArgument("id") { defaultValue = "" }),
                    ) { entry ->
                        val smartId = entry.arguments?.getString("id").orEmpty()
                        val smartVM: com.aurora.music.viewmodel.SmartPlaylistViewModel = viewModel()
                        androidx.compose.runtime.LaunchedEffect(smartId) { smartVM.load(smartId) }
                        val smartState by smartVM.state.collectAsStateWithLifecycle()
                        com.aurora.music.ui.screens.library.SmartPlaylistEditScreen(
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
                        if (editing && localMode) com.aurora.music.ui.screens.profile.LocalProfileDialog(onDismiss = { editing = false })
                        val homeVM: HomeViewModel = viewModel()
                        val homeState by homeVM.state.collectAsStateWithLifecycle()
                        ProfileScreen(
                            contentPadding = inner,
                            username = profileAppearance.name,
                            server = session?.server ?: "",
                            serverLabel = session?.typeLabel?.localizedMediaType() ?: "",
                            bannerUrl = profileAppearance.bannerUrl,
                            onEditProfile = if (localMode) ({ editing = true }) else null,
                            avatarUrl = profileAppearance.avatarUrl,
                            playlists = homeState.data.playlists,
                            artists = homeState.data.artists,
                            onBack = { navController.popBackStack() },
                            onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                            onOpenDetail = { kind, id -> openDetail(kind, id) },
                        )
                    }
                    composable(Routes.DETAIL) { entry ->
                        val kind = entry.arguments?.getString("kind").orEmpty()
                        val id = entry.arguments?.getString("id").orEmpty()
                        detailContent(kind, id, viewModel(), inner, { navController.popBackStack() }, { k, i -> openDetail(k, i) })
                    }
                    composable(
                        Routes.TAG_EDIT,
                        arguments = listOf(androidx.navigation.navArgument("songId") { defaultValue = "" }),
                    ) { entry ->
                        val songId = entry.arguments?.getString("songId").orEmpty()
                        val tagVM: com.aurora.music.viewmodel.TagEditViewModel = viewModel()
                        androidx.compose.runtime.LaunchedEffect(songId) { tagVM.load(songId) }
                        val tagState by tagVM.state.collectAsStateWithLifecycle()
                        com.aurora.music.ui.screens.detail.TagEditScreen(
                            contentPadding = inner,
                            state = tagState,
                            onEdit = tagVM::edit,
                            onMatch = tagVM::matchOnline,
                            onApplyMatch = tagVM::applyMatch,
                            // auto-identify fingerprints the decodable local file not for server items
                            onIdentify = if (container.acoustId.available && tagState.localFile) ({ tagVM.identify() }) else null,
                            identifying = tagState.identifying,
                            onBack = { navController.popBackStack() },
                            confirm = { confirm(it) },
                        )
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
                        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                        val twoPane = windowLayout.useNavigationRail && maxWidth >= 840.dp
                        fun open(route: String) {
                            if (twoPane) {
                                paneRoute = route
                                paneTouched = true
                                paneNav.navigate(route) { popUpTo(0) { inclusive = true }; launchSingleTop = true }
                            } else navController.navigate(route)
                        }
                        val advanced = setOf(Routes.SETTINGS_LOUDNESS, Routes.SETTINGS_ADVANCED_AUDIO, Routes.SIGNAL_PATH)
                        androidx.compose.runtime.LaunchedEffect(simpleMode, paneRoute, twoPane) {
                            if (twoPane && simpleMode && paneRoute in advanced) open(Routes.SETTINGS_PLAYBACK)
                        }
                        androidx.compose.runtime.LaunchedEffect(twoPane) {
                            if (!twoPane && paneTouched && currentRoute == Routes.SETTINGS) {
                                paneTouched = false
                                navController.navigate(paneRoute)
                            }
                        }
                        Row(Modifier.fillMaxSize()) {
                            Box(if (twoPane) Modifier.width(360.dp).fillMaxHeight() else Modifier.weight(1f).fillMaxHeight()) {
                                androidx.compose.runtime.CompositionLocalProvider(
                                    com.aurora.music.ui.screens.settings.LocalSelectedSettingsRoute provides if (twoPane) paneRoute else null,
                                ) {
                                SettingsScreen(
                                    avatarUrl = profileAppearance.avatarUrl,
                                    contentPadding = inner,
                                    username = profileAppearance.name,
                                    server = session?.server ?: "",
                                    onBack = { navController.popBackStack() },
                                    onOpenPlayback = { open(Routes.SETTINGS_PLAYBACK) },
                                    onOpenOutput = { open(Routes.SETTINGS_OUTPUT) },
                                    onOpenNetwork = { open(Routes.SETTINGS_NETWORK) },
                                    onOpenLoudness = { open(Routes.SETTINGS_LOUDNESS) },
                                    onOpenAdvancedAudio = { open(Routes.SETTINGS_ADVANCED_AUDIO) },
                                    onOpenAlarm = { open(Routes.SETTINGS_ALARM) },
                                    onOpenSignalPath = { open(Routes.SIGNAL_PATH) },
                                    onOpenEq = { open(Routes.SETTINGS_EQ) },
                                    onOpenVisualizer = { open(Routes.SETTINGS_VISUALIZER) },
                                    onOpenSonic = { open(Routes.SETTINGS_SONIC) },
                                    onOpenSources = { open(Routes.SETTINGS_SOURCES) },
                                    onOpenDownloads = { open(Routes.SETTINGS_STORAGE) },
                                    onOpenAppearance = { open(Routes.SETTINGS_APPEARANCE) },
                                    onOpenLanguage = { open(Routes.SETTINGS_LANGUAGE) },
                                    onOpenGestures = { open(Routes.SETTINGS_GESTURES) },
                                    onOpenIntegrations = { open(Routes.SETTINGS_INTEGRATIONS) },
                                    onOpenPermissions = { open(Routes.SETTINGS_PERMISSIONS) },
                                    onOpenAbout = { open(Routes.SETTINGS_ABOUT) },
                                    onOpenProfile = { navController.navigate(Routes.PROFILE) },
                                    onOpenAccounts = { open(Routes.SETTINGS_ACCOUNTS) },
                                    onOpenBackup = { open(Routes.SETTINGS_BACKUP) },
                                    onLogout = { logout() },
                                    onReplayTour = { onboarding.restart(); navController.navigate(Routes.SETUP) },
                                )
                                }
                            }
                            if (twoPane) {
                                Spacer(Modifier.width(16.dp))
                                Box(
                                    Modifier.weight(1f).fillMaxHeight().padding(top = 8.dp)
                                        .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)),
                                ) {
                                    androidx.compose.runtime.CompositionLocalProvider(
                                        com.aurora.music.ui.screens.settings.LocalSettingsPaneRoots provides paneRoots,
                                    ) {
                                    NavHost(
                                        navController = paneNav,
                                        startDestination = paneRoute,
                                        modifier = Modifier.widthIn(max = 840.dp).fillMaxSize(),
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
                        com.aurora.music.ui.screens.stats.ListeningHistoryScreen(contentPadding = inner, onBack = { navController.popBackStack() }, onPlay = { playById(it) })
                    }
                    composable(Routes.DUPLICATES) {
                        val dupVM: com.aurora.music.viewmodel.DuplicatesViewModel = viewModel()
                        val dupState by dupVM.state.collectAsStateWithLifecycle()
                        com.aurora.music.ui.screens.library.DuplicatesScreen(
                            contentPadding = inner,
                            loading = dupState.loading,
                            scanned = dupState.scanned,
                            groups = dupState.groups,
                            currentSongId = playerState.current.id,
                            onBack = { navController.popBackStack() },
                            onPlay = { s -> playerVM.playAll(listOf(s), 0) },
                        )
                    }
                    composable(Routes.STATS) {
                        com.aurora.music.ui.screens.stats.ListeningStatsScreen(contentPadding = inner, onBack = { navController.popBackStack() }, onPlay = { playById(it) }, onOpenDetail = { k, i -> openDetail(k, i) })
                    }
                    composable(Routes.RADIO) {
                        com.aurora.music.ui.screens.radio.RadioScreen(
                            contentPadding = inner,
                            onBack = { navController.popBackStack() },
                            onPlay = { playerVM.play(it) },
                        )
                    }
                    composable(Routes.PODCASTS) {
                        com.aurora.music.ui.screens.podcasts.PodcastsScreen(
                            contentPadding = inner,
                            onBack = { navController.popBackStack() },
                            onOpenPodcast = { p ->
                                navController.navigate(Routes.podcastDetail(p.feedUrl, p.displayTitle, p.imageUrl.orEmpty(), p.author.orEmpty()))
                            },
                        )
                    }
                    composable(
                        Routes.PODCAST_DETAIL,
                        arguments = listOf(
                            androidx.navigation.navArgument("feed") { defaultValue = "" },
                            androidx.navigation.navArgument("title") { defaultValue = "" },
                            androidx.navigation.navArgument("image") { defaultValue = "" },
                            androidx.navigation.navArgument("author") { defaultValue = "" },
                        ),
                    ) { entry ->
                        com.aurora.music.ui.screens.podcasts.PodcastDetailScreen(
                            contentPadding = inner,
                            feedUrl = entry.arguments?.getString("feed").orEmpty(),
                            title = entry.arguments?.getString("title").orEmpty(),
                            imageUrl = entry.arguments?.getString("image").orEmpty(),
                            author = entry.arguments?.getString("author").orEmpty(),
                            onBack = { navController.popBackStack() },
                            onPlay = { playerVM.play(it) },
                        )
                    }
                }
                }
                if (rail) {
                    AnimatedVisibility(
                        visible = shell.sidePanel,
                        enter = expandHorizontally(tween(240)) + fadeIn(tween(200)),
                        exit = shrinkHorizontally(tween(220)) + fadeOut(tween(160)),
                    ) {
                        val pane = sidePane ?: PlayerPane.QUEUE
                        NowPlayingSidePanel(
                            pane = pane,
                            onSelect = { sidePaneName = it.name },
                            onClose = { sidePaneName = null },
                            content = { target, modifier -> nowPlayingPane(target, modifier) },
                            actions = { target -> nowPlayingPaneActions(target) },
                            modifier = Modifier.width(TabletMetrics.SidePanelWidth).fillMaxHeight()
                                .windowInsetsPadding(WindowInsets.statusBars)
                                .padding(top = panelSpacing, end = pageMargin.coerceAtLeast(12.dp),
                                    bottom = inner.calculateBottomPadding()),
                        )
                    }
                }
                }
            }
            if (dockVisible) {
                PlaybackDock(
                    state = playerState,
                    openPane = if (shell.sidePanel) sidePane else null,
                    onExpand = { if (playerState.current.id.startsWith("aurora-mix:")) showMix = true else playerVM.setExpanded(true) },
                    onTogglePlay = { playerVM.togglePlay() },
                    onPrevious = { playerVM.previous() },
                    onNext = { playerVM.next() },
                    onSeek = { playerVM.seekTo(it) },
                    onToggleLike = { playerVM.toggleLikeCurrent() },
                    onOpenOutput = { showOutput = true },
                    onPane = { showPane(it) },
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                        .onSizeChanged { dockHeight = with(density) { it.height.toDp() } },
                )
            }

            MaterialTheme(colorScheme = playerColors) {
            AnimatedVisibility(
                visible = playerState.expanded,
                enter = slideInVertically(animationSpec = tween(320)) { it } + fadeIn(tween(220)),
                exit = slideOutVertically(animationSpec = tween(280)) { it } + fadeOut(tween(180)),
            ) {
                PlayerScreen(
                    state = playerState,
                    videoPlayer = playerVM.videoPlayer,
                    onVideoQualityChange = playerVM::setVideoQuality,
                    onRequestVideo = { playerVM.requestMusicVideo { confirm(it) } },
                    onVideoVisibleChange = playerVM::setMusicVideoVisible,
                    onCollapse = { playerVM.setExpanded(false) },
                    onTogglePlay = { playerVM.togglePlay() },
                    onNext = { playerVM.next() },
                    onPrevious = { playerVM.previous() },
                    onSeek = { playerVM.seekTo(it) },
                    onToggleLike = { playerVM.toggleLikeCurrent() },
                    onToggleShuffle = { playerVM.toggleShuffle() },
                    onCycleRepeat = { playerVM.cycleRepeat() },
                    onOpenSpeedPitch = { showSpeedSheet = true },
                    onOpenQueue = { showQueue = true },
                    onGoToAlbum = {
                        val id = playerState.current.albumId
                        if (id.isNotBlank()) { playerVM.setExpanded(false); openDetail("album", id) }
                    },
                    onGoToArtist = {
                        val id = playerState.current.artistId
                        if (id.isNotBlank()) { playerVM.setExpanded(false); openDetail("artist", id) }
                    },
                    onOpenOutput = { showOutput = true },
                    onOpenSleep = { showSleep = true },
                    onOpenVisualizer = { showVisualizer = true },
                    onOpenSignalPath = {
                        playerVM.setExpanded(false)
                        showQueue = false
                        showSpeedSheet = false
                        showOutput = false
                        showSleep = false
                        navController.navigate(Routes.SIGNAL_PATH) { launchSingleTop = true }
                    },
                    onSonicRadio = { playerVM.startSonicRadio(onResult = { confirm(it) }) },
                    onAutoDj = { playerVM.startAutoDj(onResult = { confirm(it) }) },
                    onOpenMix = { playerVM.setExpanded(false); mixQueue = playerState.queue.filterNot { it.id.startsWith("aurora-mix:") }; showMix = true },
                    gestures = gesturePrefs,
                    requestedPane = playerPaneRequest,
                    onPaneRequestHandled = { playerPaneRequest = null },
                    paneContent = if (windowLayout.useNavigationRail) nowPlayingPane else null,
                    paneActions = nowPlayingPaneActions,
                    onSplitChange = { split ->
                        scope.launch { container.settingsStore.setTabletSetting(com.aurora.music.data.TabletSetting.PLAYER_SPLIT, split) }
                    },
                )
            }

            AnimatedVisibility(
                visible = showQueue,
                enter = slideInVertically(animationSpec = tween(300)) { it } + fadeIn(tween(200)),
                exit = slideOutVertically(animationSpec = tween(260)) { it } + fadeOut(tween(160)),
            ) {
                com.aurora.music.ui.screens.player.QueueScreen(
                    queue = playerState.queue,
                    currentIndex = playerState.currentIndex,
                    editable = !playerState.isMix,
                    isPlaying = playerState.isPlaying,
                    onJump = { playerVM.jumpTo(it) },
                    onRemove = { playerVM.removeFromQueue(it) },
                    onMove = { from, to -> playerVM.moveQueueItem(from, to) },
                    onClear = { playerVM.clearQueue() },
                    onSaveAsPlaylist = { name -> playerVM.saveQueueAsPlaylist(name) { confirm(it) } },
                    onClose = { showQueue = false },
                    onOpenMix = { playerVM.setExpanded(false); mixQueue = playerState.queue.filterNot { it.id.startsWith("aurora-mix:") }; showQueue = false; showMix = true },
                )
            }
            }

            AnimatedVisibility(
                visible = showVisualizer,
                enter = fadeIn(tween(220)),
                exit = fadeOut(tween(180)),
            ) {
                com.aurora.music.ui.screens.visualizer.VisualizerScreen(
                    state = playerState,
                    onClose = { showVisualizer = false },
                )
            }
            if (onboarding.phase == SetupPhase.AUTH || onboarding.phase == SetupPhase.TASK) setupUi()
        }
    }

    BackHandler(enabled = playerState.expanded && !showMix) { playerVM.setExpanded(false) }

    MaterialTheme(colorScheme = playerColors) {
    if (showSpeedSheet) {
        SpeedPitchSheet(
            speed = playerState.speed,
            pitch = playerState.pitch,
            matchPitch = playerState.matchPitch,
            onSpeed = { playerVM.setSpeed(it) },
            onPitch = { playerVM.setPitch(it) },
            onMatchPitch = { playerVM.setMatchPitch(it) },
            onReset = { playerVM.resetSpeedPitch() },
            onDismiss = { showSpeedSheet = false },
        )
    }

    if (showOutput) {
        com.aurora.music.ui.screens.player.OutputDeviceSheet(
            currentId = playerVM.preferredDeviceId(),
            onSelect = { playerVM.setPreferredDevice(it) },
            onDismiss = { showOutput = false },
        )
    }
    if (showSleep) {
        com.aurora.music.ui.screens.player.SleepTimerSheet(
            currentMinutes = playerState.sleepTimerMinutes,
            endOfTrack = playerState.sleepEndOfTrack,
            onSelect = { playerVM.setSleepTimer(it) },
            onEndOfTrack = { playerVM.setSleepEndOfTrack() },
            onDismiss = { showSleep = false },
        )
    }
    }

    BackHandler(enabled = showQueue) { showQueue = false }
    BackHandler(enabled = showVisualizer && !showMix) { showVisualizer = false }
    BackHandler(enabled = onboarding.phase != SetupPhase.HIDDEN) {
        when (onboarding.phase) {
            SetupPhase.QUESTIONS -> if (onboarding.page > 0) onboarding.page-- else finishOnboarding()
            SetupPhase.SOURCES -> { onboarding.phase = SetupPhase.QUESTIONS; onboarding.page = 2 }
            SetupPhase.TASKS -> onboarding.phase = SetupPhase.SOURCES
            SetupPhase.AUTH -> leaveOnboardingAuth(false)
            SetupPhase.TASK -> leaveOnboardingTask(null)
            else -> Unit
        }
    }
    if (showMix) {
        com.aurora.music.ui.screens.player.MixScreen(mixVM, mixQueue, onClose = { showMix = false })
    }
}

@Composable
private fun DownloadProgressBanner(count: Int, progress: Float) {
    val classic = com.aurora.music.ui.theme.LocalUiPrefs.current.themeStyle == com.aurora.music.data.ThemeStyle.AURORA
    val animated by androidx.compose.animation.core.animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(300),
        label = "dlProgress",
    )
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
                if (count == 1) appString(R.string.text_downloading_1_song_75e402) else appString(R.string.text_downloading_songs_f16c02, (count)),
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
        Text(
            "${(animated * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private suspend fun playlistMutation(block: suspend () -> Boolean): Boolean = try {
    block()
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (_: Exception) {
    false
}

@Composable
private fun FloatingNav(currentRoute: String?, onNavigate: (String) -> Unit) {
    val classic = com.aurora.music.ui.theme.LocalUiPrefs.current.themeStyle == com.aurora.music.data.ThemeStyle.AURORA
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (classic) Modifier.clip(RoundedCornerShape(26.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f))
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(26.dp))
                else Modifier.auroraPanel(MaterialTheme.shapes.extraLarge, emphasized = true))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        topLevelDestinations.forEach { dest ->
            val selected = currentRoute == dest.route
            val bg by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                label = "navBg",
            )
            val content = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            Row(
                modifier = Modifier
                    .weight(if (selected) 1.4f else 1f)
                    .clip(if (classic) RoundedCornerShape(50) else MaterialTheme.shapes.small)
                    .background(bg)
                    .clickable { onNavigate(dest.route) }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (selected) dest.selectedIcon else dest.unselectedIcon, dest.label, tint = content, modifier = Modifier.size(22.dp))
                AnimatedVisibility(visible = selected) {
                    Row {
                        Spacer(Modifier.width(8.dp))
                        Text(dest.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = content)
                    }
                }
            }
        }
    }
}
