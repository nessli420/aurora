package com.aurora.music.ui

import androidx.compose.animation.AnimatedVisibility
import com.aurora.music.ui.theme.fade
import com.aurora.music.ui.theme.glassEdge
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import com.aurora.music.ui.theme.LocalGlass
import androidx.compose.ui.graphics.graphicsLayer
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
import com.aurora.music.desktop.platform.BuildInfo
import com.aurora.music.ui.whatsnew.WhatsNewCatalog
import com.aurora.music.ui.whatsnew.WhatsNewLauncher
import com.aurora.music.ui.whatsnew.WhatsNewReturnPill
import com.aurora.music.ui.whatsnew.WhatsNewTour
import kotlinx.coroutines.flow.first
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
import com.aurora.music.data.Pin
import com.aurora.music.data.RecapPeriod
import com.aurora.music.data.RecapWindow
import com.aurora.music.data.ServerType
import com.aurora.music.data.TabletSetting
import com.aurora.music.data.ThemeStyle
import com.aurora.music.desktop.player.PlayerController
import com.aurora.music.desktop.player.PlayerUiState
import com.aurora.music.desktop.player.settledState
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
import com.aurora.music.ui.components.PaneDivider
import com.aurora.music.ui.components.PlaybackDock
import com.aurora.music.ui.components.TabletNavigationRail
import com.aurora.music.ui.components.TabletSidebar
import com.aurora.music.ui.components.rememberPaneWidth
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
import com.aurora.music.ui.screens.settings.ConnectSettingsScreen
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
import com.aurora.music.ui.screens.settings.LocalSettingsPaneExpand
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
import com.aurora.music.ui.theme.PlayerTheme
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.floor
import com.aurora.music.ui.theme.themedCorner

private val unportedRoutes = emptySet<String>()
private val splitKinds = listOf("album", "artist", "playlist", "liked", "smart")
private val mouseGestures = GesturePrefs(swipeArtwork = false)
private const val LIKES_RESUME_INTERVAL_NS = 5 * 60_000_000_000L

internal fun likesResumeDue(pulledAt: Long?, now: Long, sessionReady: Boolean?, offline: Boolean): Boolean =
    sessionReady == true && !offline && pulledAt?.let { now - it < LIKES_RESUME_INTERVAL_NS } != true

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AuroraApp(
    navController: NavHostController = rememberNavController(),
    shortcuts: Flow<Shortcut> = emptyFlow(),
    fullscreen: Boolean = false,
    onFullscreenChange: (Boolean) -> Unit = {},
    titleBar: @Composable () -> Unit = {},
) {
    val owner = LocalViewModelStoreOwner.current ?: rememberRootOwner()
    val backDispatcher = remember { BackDispatcher() }
    val language by AppStrings.languageTag.collectAsState()
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, LocalBackGestureDispatcher provides backDispatcher) {
        // the backdrop runs under the title bar so its glow isn't cut off at the top of the page
        Box(Modifier.fillMaxSize()) {
            AmbientBackground()
            Column(Modifier.fillMaxSize()) {
                titleBar()
                Box(Modifier.weight(1f)) {
                    key(language) { Shell(navController, owner, backDispatcher::back, shortcuts, fullscreen, onFullscreenChange) }
                }
            }
        }
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
    val desktopSettings = container.desktopSettings
    val repository = container.repository
    var showImport by remember { mutableStateOf(false) }
    val authVM = viewModel(viewModelStoreOwner = rootOwner) { AuthViewModel(container) }

    val playerState by remember(player) { player.settledState }.collectAsStateWithLifecycle(player.state.value.settled())
    val volume by player.volume.collectAsStateWithLifecycle()
    val authState by authVM.state.collectAsStateWithLifecycle()
    val musicFolders by authVM.musicFolders.collectAsStateWithLifecycle()
    val sessionReady by container.sessionReady.collectAsStateWithLifecycle()
    val session by store.session.collectAsStateWithLifecycle(initialValue = null)
    val savedSessions by store.savedSessions.collectAsStateWithLifecycle(initialValue = emptyList())
    val downloadsMap by container.downloadManager.downloads.collectAsStateWithLifecycle()
    val localMode = session?.type == ServerType.LOCAL
    // equal appearances keep one instance, so the nav graph builder that captures it stays the same
    val profileAppearance = rememberProfileAppearance(session).let { remember(it) { it } }
    val currentServer = session?.server ?: ""
    val allPins by store.pins.collectAsStateWithLifecycle(initialValue = emptyList())
    val pins = remember(allPins, currentServer) { allPins.filter { it.serverId == currentServer } }
    val offlineMode by container.offline.collectAsStateWithLifecycle()
    val accountEpoch by container.accountEpoch.collectAsStateWithLifecycle()
    val serverTagEditing = session?.let { repository.supportsServerTagEdit } ?: false
    var showMix by remember { mutableStateOf(false) }
    var mixQueue by remember { mutableStateOf<List<Song>>(emptyList()) }
    val mixVM = viewModel(viewModelStoreOwner = rootOwner, key = "mix-${session?.server}-${session?.username}") { com.aurora.music.viewmodel.MixViewModel(container) }
    val openMix: () -> Unit = {
        player.setExpanded(false)
        mixQueue = player.state.value.queue.filterNot { it.id.startsWith("aurora-mix:") }
        showMix = true
    }
    // a collection mix is built from the whole tracklist and plays straight away in the studio
    val mixCollection: (String, String, String) -> Unit = { kind, id, name ->
        player.setExpanded(false)
        mixQueue = emptyList()
        showMix = true
        mixVM.mixCollection(kind, id, name) { }
    }
    val editTags: (Song) -> Unit = { song -> navController.navigate(Routes.tagEdit(song.id)) }
    val libraryReload by container.libraryReload.collectAsStateWithLifecycle()
    val downloadedIds by produceState(emptySet<String>(), downloadsMap, session, sessionReady, offlineMode, accountEpoch, libraryReload) {
        value = withContext(Dispatchers.Default) { repository.downloadedIds() }
    }
    // only the count lives here; per-chunk progress recomposes just the banner
    val activeDownloads by remember(container) {
        container.downloadManager.states.map { states ->
            states.values.count { it is DownloadState.Queued || it is DownloadState.Downloading }
        }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = 0)
    val simpleMode by store.simpleMode.collectAsStateWithLifecycle(initialValue = false)
    val unsupportedAccount by container.unsupportedAccount.collectAsStateWithLifecycle()
    val savedLibraryWidth by desktopSettings.libraryListWidth.collectAsStateWithLifecycle(initialValue = null)
    val savedSettingsWidth by desktopSettings.settingsListWidth.collectAsStateWithLifecycle(initialValue = null)
    val savedPanelWidth by desktopSettings.sidePanelWidth.collectAsStateWithLifecycle(initialValue = null)

    val snackbarHostState = remember { SnackbarHostState() }
    val connect = com.aurora.music.desktop.connect.LocalConnect.current
    LaunchedEffect(container, player, connect) {
        merge(container.sourceErrors, player.messages, connect?.messages ?: kotlinx.coroutines.flow.emptyFlow()).collect { message ->
            if (snackbarHostState.currentSnackbarData == null) snackbarHostState.showSnackbar(message)
        }
    }
    val scope = rememberCoroutineScope()
    // versions in the open what's new tour, the page it's on, and whether "Try it" has the real screen up
    var whatsNew by remember { mutableStateOf<List<String>?>(null) }
    var whatsNewPage by remember { mutableStateOf(0) }
    var whatsNewTrying by remember { mutableStateOf(false) }
    LaunchedEffect(container) {
        if (whatsNew != null) return@LaunchedEffect
        val current = BuildInfo.VERSION_NAME
        val lastSeen = store.whatsNewSeen.first()
        val unseen = com.aurora.music.data.WhatsNew.unseen(WhatsNewCatalog.releases, lastSeen, current) { it.version }
        // no record and no accounts yet reads as a new install, which gets no tour
        val fresh = lastSeen == null && store.savedSessions.first().isEmpty()
        when (com.aurora.music.data.WhatsNew.onLaunch(fresh, store.whatsNewEnabled.first(), lastSeen, current, unseen.isNotEmpty())) {
            com.aurora.music.data.WhatsNew.Launch.SHOW -> { whatsNew = unseen.map { it.version }; whatsNewPage = 0 }
            com.aurora.music.data.WhatsNew.Launch.MARK_SEEN -> store.setWhatsNewSeen(current)
            com.aurora.music.data.WhatsNew.Launch.NOTHING -> Unit
        }
    }
    LaunchedEffect(Unit) {
        WhatsNewLauncher.opened.collect { version ->
            whatsNew = listOf(version)
            whatsNewPage = 0
            whatsNewTrying = false
        }
    }
    fun closeWhatsNew() {
        whatsNew = null
        whatsNewTrying = false
        scope.launch { store.setWhatsNewSeen(BuildInfo.VERSION_NAME) }
    }
    fun confirm(message: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }
    var matchSong by remember { mutableStateOf<Song?>(null) }
    val hiddenSongs by container.trackChoices.hidden.collectAsStateWithLifecycle()
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

    // the window resumes on every focus, so stars from elsewhere re-pull at most every 5 min, never offline or before sign-in
    var likesPulledAt by remember { mutableStateOf<Long?>(null) }
    // the player pulls on sign-in itself, so that counts as the latest pull
    LaunchedEffect(sessionReady) {
        if (sessionReady == true && !container.offline.value) likesPulledAt = System.nanoTime()
    }
    // a unified or merge toggle renames every id, so its hearts can't wait for the window
    LaunchedEffect(container, player) {
        container.libraryReload.drop(1).collect {
            likesPulledAt = null
            if (container.sessionReady.value == true && !container.offline.value) { likesPulledAt = System.nanoTime(); player.refreshLikes(restart = true) }
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            val now = System.nanoTime()
            if (event == Lifecycle.Event.ON_RESUME && likesResumeDue(likesPulledAt, now, container.sessionReady.value, container.offline.value)) {
                likesPulledAt = now
                player.refreshLikes()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var hadActiveDownloads by remember { mutableStateOf(false) }
    LaunchedEffect(activeDownloads) {
        if (activeDownloads > 0) {
            hadActiveDownloads = true
        } else if (hadActiveDownloads) {
            hadActiveDownloads = false
            val failed = container.downloadManager.states.value.values.count { it is DownloadState.Failed }
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
    val aiDjActive = player.aiDj?.state?.collectAsStateWithLifecycle()?.value?.active == true
    val sidePane = sidePaneName?.let { runCatching { PlayerPane.valueOf(it) }.getOrNull() }?.takeUnless { aiDjActive && it == PlayerPane.QUEUE }
    val panelPane = rememberPaneWidth(savedPanelWidth)
    val panelWidth = panelPane.resolve(TabletMetrics.SidePanelWidth, TabletMetrics.SidePanelMinWidth, windowLayout.maxSidePanelWidth)
    val shell = windowLayout.shell(panelRequested = rail && sidePane != null && playerState.hasTrack, panelWidth = panelWidth)
    val uiPrefs = LocalUiPrefs.current
    val libraryReloads by container.libraryReload.collectAsStateWithLifecycle()
    val exploreAvailable = remember(sessionReady, accountEpoch, libraryReloads) { container.repository.supportsExplore }
    val navLayout = remember(uiPrefs.navLayout, simpleMode, exploreAvailable) {
        NavMenu.parse(uiPrefs.navLayout).visible(simpleMode, discovery = exploreAvailable).ported()
    }
    val navGap = if (rail) (TabletMetrics.NavGap + (uiPrefs.tabletNavGap - TabletSetting.NAV_GAP.default).dp).coerceAtLeast(0.dp) else 0.dp
    val gutter = LocalPageGutter.current
    val panelSpacing = uiPrefs.tabletPanelSpacing.dp.coerceAtLeast(4.dp)
    val density = LocalDensity.current
    var dockHeight by remember { mutableStateOf(0.dp) }
    val dockVisible = rail && playerState.showsBar
    val detailId = if (currentRoute == Routes.DETAIL) backStackEntry?.arg("id") else null
    var playlistsVersion by remember { mutableStateOf(0) }
    val sidebarPlaylists by produceState(emptyList<Playlist>(), sessionReady, session?.server, accountEpoch, libraryReload, offlineMode, playlistsVersion) {
        if (sessionReady == true && session != null) value = runCatching { repository.allPlaylists() }.getOrNull() ?: value
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

    val glass = LocalGlass.current
    // the opaque player hides the shell once it has slid in, and shows it again before its exit reveals anything
    val shellAlpha by animateFloatAsState(if (playerState.expanded) 0f else 1f,
        if (glass) tween(260) else tween(0, delayMillis = if (playerState.expanded) 340 else 0), label = "shellFade")

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

    var showShortcuts by remember { mutableStateOf(false) }
    // a tap jumps, a held key scrubs at a steady rate; repeats arrive faster than the engine reports the new position,
    // so they step on from the last target, and the engine gets at most one seek per interval with the latest target
    val keySeek = remember { object { var target = 0f; var at = 0L; var sentAt = 0L; var song = ""; var pending: kotlinx.coroutines.Job? = null } }
    val onShortcut by rememberUpdatedState<(Shortcut) -> Unit> { shortcut ->
        val hasTrack = playerState.hasTrack
        val seekBy = { direction: Int ->
            val duration = playerState.durationSec
            if (hasTrack && duration > 0) {
                val now = System.currentTimeMillis()
                val gap = now - keySeek.at
                val continuing = keySeek.song == playerState.current.id && gap < KEY_SEEK_WINDOW_MS
                val step = if (continuing && gap < KEY_REPEAT_GAP_MS) SCRUB_SECONDS_PER_SECOND * gap / 1000f else SEEK_STEP_SECONDS.toFloat()
                keySeek.target = ((if (continuing) keySeek.target else player.state.value.positionSec) + direction * step).coerceIn(0f, duration.toFloat())
                keySeek.at = now
                keySeek.song = playerState.current.id
                val send = { keySeek.sentAt = System.currentTimeMillis(); player.seekTo(keySeek.target / duration) }
                val wait = KEY_SEEK_SEND_MS - (now - keySeek.sentAt)
                if (wait <= 0) { keySeek.pending?.cancel(); send() }
                else if (keySeek.pending?.isActive != true) keySeek.pending = scope.launch { kotlinx.coroutines.delay(wait); send() }
            }
        }
        when (shortcut) {
            Shortcut.PLAY_PAUSE -> if (hasTrack) player.togglePlay()
            Shortcut.PREVIOUS -> if (hasTrack) player.previous()
            Shortcut.NEXT -> if (hasTrack) player.next()
            Shortcut.SEEK_BACK -> seekBy(-1)
            Shortcut.SEEK_FORWARD -> seekBy(1)
            Shortcut.VOLUME_UP -> player.setVolume(player.volume.value + VOLUME_STEP)
            Shortcut.VOLUME_DOWN -> player.setVolume(player.volume.value - VOLUME_STEP)
            Shortcut.MUTE -> player.toggleMute()
            Shortcut.SHUFFLE -> player.toggleShuffle()
            Shortcut.REPEAT -> player.cycleRepeat()
            Shortcut.SETTINGS -> if (showChrome) {
                player.setExpanded(false)
                showVisualizer = false
                navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
            }
            Shortcut.SHORTCUTS -> showShortcuts = !showShortcuts
            Shortcut.LIKE -> if (hasTrack) player.toggleLikeCurrent()
            Shortcut.SEARCH -> if (showChrome) {
                player.setExpanded(false)
                showVisualizer = false
                navigateTopLevel(Routes.SEARCH)
            }
            Shortcut.QUEUE -> if (hasTrack && !aiDjActive) {
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
    if (showShortcuts) com.aurora.music.ui.components.ShortcutsDialog(onDismiss = { showShortcuts = false })
    var lyricsFullscreen by remember { mutableStateOf(false) }
    LaunchedEffect(playerState.expanded, fullscreen) {
        if (fullscreen && !player.state.value.expanded) onFullscreenChange(false)
        if (!fullscreen) lyricsFullscreen = false
    }

    val aiDjState = player.aiDj?.state?.collectAsStateWithLifecycle()?.value
    val openAiDj: () -> Unit = {
        if (aiDjState?.active != true) player.startAiDj { confirm(it) } else player.setExpanded(true)
    }
    var showAiDjSheet by remember { mutableStateOf(false) }
    val aiDjSpoken by store.aiDjSpoken.collectAsStateWithLifecycle(true)
    val holdAiDj: () -> Unit = {
        if (container.aiDjVoiceModels.model() == null) showAiDjSheet = true
        else if (!player.aiDjListen()) confirm(appString(R.string.ai_dj_mic_denied))
    }
    val aiDjControl: (@Composable () -> Unit)? = if (aiDjState?.active == true) ({
        com.aurora.music.ui.screens.aidj.AiDjPlayerButton(aiDjState, { showAiDjSheet = true }, holdAiDj, player::aiDjHear)
    }) else null
    val aiDjStage: (@Composable (Modifier) -> Unit)? = if (aiDjState != null && (aiDjState.speech != null || aiDjState.listening || aiDjState.thinking)) ({ modifier ->
        com.aurora.music.ui.screens.aidj.AiDjStage(aiDjState, modifier)
    }) else null
    LaunchedEffect(aiDjState?.missed) { if (aiDjState?.missed == true) confirm(appString(R.string.ai_dj_missed)) }
    if (showAiDjSheet && aiDjState?.active == true) com.aurora.music.ui.screens.aidj.AiDjSheet(
        state = aiDjState,
        spoken = aiDjSpoken,
        speakerModels = container.aiDjSpeakerModels.takeIf { com.aurora.music.ai.speakerLanguage(com.aurora.music.localization.AppStrings.locale.language) != null },
        voiceModels = container.aiDjVoiceModels,
        onSteer = player::steerAiDj,
        onSwitch = player::switchAiDj,
        onStop = player::stopAiDj,
        onSpoken = { scope.launch { store.setAiDjSpoken(it) } },
        onDownloadSpeaker = container::downloadAiDjSpeakerModel,
        onDownloadVoice = container::downloadAiDjVoiceModel,
        onDismiss = { showAiDjSheet = false },
    )
    val nowPlayingPane: @Composable (PlayerPane, Modifier) -> Unit = { pane, modifier ->
        when (pane) {
            PlayerPane.LYRICS -> LivePlayer(player) { live -> LyricsPane(live, onSeek = { player.seekTo(it) }, modifier = modifier.clip(RoundedCornerShape(themedCorner(20.dp)))) }
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
            PlayerPane.RELATED -> com.aurora.music.ui.screens.player.RelatedPane(
                playerState.current,
                onOpenDetail = { kind, id -> player.setExpanded(false); openDetail(kind, id) },
                onPlayAll = { songs, index -> player.playAll(songs, index) },
                modifier = modifier,
            )
        }
    }
    val nowPlayingPaneActions: @Composable (PlayerPane) -> Unit = { pane ->
        if (pane == PlayerPane.QUEUE) QueueActions(
            queue = playerState.queue,
            currentIndex = playerState.currentIndex,
            editable = !playerState.isMix,
            onClear = { player.clearQueue() },
            onOpenMix = openMix,
            onSaveAsPlaylist = { name ->
                player.saveQueueAsPlaylist(name) {
                    confirm(it)
                    playlistsVersion++
                }
            },
        )
    }

    val detailContent: @Composable (String, String, DetailViewModel, PaddingValues, () -> Unit, (String, String) -> Unit, (() -> Unit)?) -> Unit =
        { kind, id, detailVM, padding, onBack, onOpen, onExpand ->
            LaunchedEffect(kind, id) { detailVM.load(kind, id) }
            val detailState by detailVM.state.collectAsStateWithLifecycle()
            LaunchedEffect(detailState.data?.tracks?.size) {
                detailState.data?.tracks?.let { ts -> player.checkLiked(ts) }
            }
            DetailScreen(
                contentPadding = padding,
                onMix = { mixCollection(kind, id, detailState.data?.info?.title ?: appString(R.string.text_collection_30c54a)) },
                state = detailState,
                onEditTags = editTags,
                serverTagEditing = serverTagEditing,
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
                hiddenIds = if (kind == "playlist") hiddenSongs[com.aurora.music.data.TrackChoices.collectionKey(kind, id)].orEmpty() else emptySet(),
                onToggleHidden = if (kind == "playlist") ({ song -> container.trackChoices.setHidden(kind, id, song.id, !container.trackChoices.isHidden(kind, id, song.id)) }) else null,
                isItemLiked = playerState.likedIds.contains(id),
                onToggleItemLike = { player.toggleLike(id, kind) },
                canLikeItem = container.repository.canLike(id),
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
                onExpand = onExpand,
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
        composable(Routes.SETTINGS_WHATS_NEW) {
            com.aurora.music.ui.screens.settings.WhatsNewSettingsScreen(padding, onBack = pop)
        }
        composable(Routes.SETTINGS_INSIGHTS) {
            com.aurora.music.ui.screens.settings.DetailedStatsScreen(padding, onBack = pop, onOpenInsights = { nav.navigate(Routes.INSIGHTS) },
                onOpenMap = { nav.navigate(Routes.LISTENING_MAP) })
        }
        composable(Routes.LISTENING_MAP) { com.aurora.music.ui.screens.stats.ListeningMapScreen(padding, onBack = pop) }
        composable(Routes.LISTENING_MAP + "/{songId}/{at}") { entry ->
            com.aurora.music.ui.screens.stats.ListeningMapScreen(padding, onBack = pop, songId = entry.arg("songId").ifBlank { null },
                at = entry.arg("at").toLongOrNull())
        }
        composable(Routes.INSIGHTS) {
            com.aurora.music.ui.screens.stats.InsightsScreen(padding, onBack = pop, onSetup = { nav.navigate(Routes.SETTINGS_INSIGHTS) },
                onOpenMap = { nav.navigate(Routes.LISTENING_MAP) })
        }
        composable(Routes.INSIGHTS + "/{period}/{date}") { entry ->
            val period = runCatching { RecapPeriod.valueOf(entry.arg("period")) }.getOrDefault(RecapPeriod.MONTH)
            val date = runCatching { LocalDate.parse(entry.arg("date")) }.getOrDefault(LocalDate.now())
            com.aurora.music.ui.screens.stats.InsightsScreen(padding, onBack = pop, onSetup = { nav.navigate(Routes.SETTINGS_INSIGHTS) },
                initialWindow = RecapWindow.containing(period, date), onOpenMap = { nav.navigate(Routes.LISTENING_MAP) })
        }
        composable(Routes.SETTINGS_CONNECT) { ConnectSettingsScreen(padding, onBack = pop) }
        composable(Routes.SETTINGS_INTEGRATIONS) {
            IntegrationsSettingsScreen(
                contentPadding = padding,
                onBack = pop,
                onOpenLyrics = { nav.navigate(Routes.SETTINGS_INTEGRATION_LYRICS) },
                onOpenLastfm = { nav.navigate(Routes.SETTINGS_INTEGRATION_LASTFM) },
                onOpenListenBrainz = { nav.navigate(Routes.SETTINGS_INTEGRATION_LISTENBRAINZ) },
                onOpenArtistInfo = { nav.navigate(Routes.SETTINGS_INTEGRATION_ARTIST_INFO) },
                onOpenDiscord = { nav.navigate(Routes.SETTINGS_INTEGRATION_DISCORD) },
                onOpenAcoustId = { nav.navigate(Routes.SETTINGS_INTEGRATION_ACOUSTID) },
            )
        }
        composable(Routes.SETTINGS_COMPARISON) {
            val comparisonVM = viewModel { com.aurora.music.viewmodel.ComparisonViewModel(container, player) }
            com.aurora.music.ui.screens.settings.ComparisonScreen(padding, onBack = pop, onOpenPresets = { nav.navigate(Routes.SETTINGS_PROCESSING_PRESETS) },
                currentSong = player.state.value.current, model = comparisonVM)
        }
        composable(Routes.SETTINGS_LISTENING) { com.aurora.music.ui.screens.settings.ListeningLevelsScreen(padding, onBack = pop) }
        composable(Routes.SETTINGS_TUNING) {
            com.aurora.music.ui.screens.settings.TuningProjectsScreen(contentPadding = padding, onBack = pop,
                onOpenRack = { nav.navigate(Routes.SETTINGS_PROCESSING_RACK) })
        }
        composable(Routes.SETTINGS_PRESET_RULES) { com.aurora.music.ui.screens.settings.PresetRulesScreen(padding, onBack = pop) }
        composable(Routes.SETTINGS_SONIC) { com.aurora.music.ui.screens.settings.SonicSettingsScreen(padding, onBack = pop) }
        composable(Routes.SETTINGS_INTEGRATION_ACOUSTID) { com.aurora.music.ui.screens.settings.AcoustIdIntegrationScreen(padding, pop) }
        composable(Routes.SETTINGS_INTEGRATION_DISCORD) {
            val settings = LocalDesktopContainer.current.settingsStore
            val discordScope = rememberCoroutineScope()
            var signingIn by remember { mutableStateOf(false) }
            com.aurora.music.ui.screens.settings.DiscordIntegrationScreen(padding, onBack = pop, onOpenDiscordLogin = { signingIn = true })
            if (signingIn) com.aurora.music.ui.screens.auth.DiscordLoginWindow(onClose = { signingIn = false }) { token ->
                signingIn = false
                discordScope.launch { settings.saveDiscord(token, "") }
            }
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
                onOpenReverb = { nav.navigate(Routes.SETTINGS_REVERB) },
            )
        }
        composable(Routes.SETTINGS_REVERB) { com.aurora.music.ui.screens.settings.ReverbSettingsScreen(padding, onBack = pop) }
        composable(Routes.SETTINGS_LYRICS) { com.aurora.music.ui.screens.settings.LyricsSettingsScreen(padding, onBack = pop) }
        composable(Routes.SETTINGS_MERGE) { com.aurora.music.ui.screens.settings.MergeOptionsScreen(padding, onBack = pop) }
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
        composable(Routes.SETTINGS_SOURCES) { SourcesSettingsScreen(padding, onBack = pop, onArtistSeparators = { nav.navigate(Routes.SETTINGS_ARTIST_SEPARATORS) }) }
        composable(Routes.SETTINGS_ARTIST_SEPARATORS) { com.aurora.music.ui.screens.settings.ArtistSeparatorsScreen(padding, onBack = pop) }
        composable(Routes.SETTINGS_STORAGE) { StorageSettingsScreen(padding, pop, onOpenYouTubeDownload = { nav.navigate(Routes.YOUTUBE_DOWNLOAD) }) }
        composable(Routes.YOUTUBE_DOWNLOAD) {
            com.aurora.music.ui.screens.youtube.YouTubeDownloadScreen(padding, onBack = pop,
                onOpenSettings = { nav.navigate(Routes.SETTINGS_STORAGE) { launchSingleTop = true } })
        }
        composable(Routes.SETTINGS_ABOUT) { AboutSettingsScreen(padding, pop) }
        composable(Routes.SETTINGS_LANGUAGE) { LanguageSettingsScreen(padding, pop) }
    }

    val startRadio: (Song) -> Unit = { song -> player.startSonicRadio(song) { confirm(it) } }
    CompositionLocalProvider(
        com.aurora.music.ui.components.LocalStartRadio provides startRadio,
        com.aurora.music.ui.components.LocalCanLike provides container.repository::canLike,
        com.aurora.music.ui.components.LocalLikeMenu provides remember(player) { com.aurora.music.ui.components.LikeMenu(player::likeOptions, player::setLikedOn, player::likedSources) },
        com.aurora.music.ui.components.LocalRepostMenu provides remember(container) { com.aurora.music.ui.components.RepostMenu(container.repository, scope, ::confirm) },
        com.aurora.music.ui.components.LocalMatchPicker provides remember { { song: Song -> matchSong = song } },
        com.aurora.music.ui.screens.player.LocalPlayerPanes provides
            (if (exploreAvailable) PlayerPane.entries else listOf(PlayerPane.LYRICS, PlayerPane.QUEUE)).let { if (aiDjActive) it - PlayerPane.QUEUE else it },
    ) {
    matchSong?.let { com.aurora.music.ui.components.MatchPickerDialog(it, ::confirm) { matchSong = null } }
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(drawerState = drawerState, drawerContainerColor = MaterialTheme.colorScheme.surface) {
                // a closed drawer stays composed for its anchors, so it skips the collection rows until it opens
                val drawerShown = !drawerState.isClosed || drawerState.isAnimationRunning
                TabletSidebar(
                    currentRoute = currentRoute,
                    username = profileAppearance.name,
                    server = session?.server ?: "",
                    avatarUrl = profileAppearance.avatarUrl,
                    layout = navLayout,
                    onOpen = { item -> closeDrawerThen { openNavItem(item) } },
                    onProfile = { closeDrawerThen { navController.navigate(Routes.PROFILE) { launchSingleTop = true } } },
                    onSettings = { closeDrawerThen { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } } },
                    playlists = if (drawerShown) sidebarPlaylists else emptyList(),
                    pins = if (drawerShown) pins else emptyList(),
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
            if (rail) {
                Box(Modifier.fillMaxHeight().graphicsLayer { alpha = shellAlpha }
                    .then(if (dockVisible) Modifier.padding(bottom = dockHeight + 12.dp) else Modifier)) {
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
                modifier = Modifier.graphicsLayer { alpha = shellAlpha }.padding(start = if (rail) shell.navWidth + navGap else 0.dp)
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
                                DownloadProgressBanner(count = activeDownloads)
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
                                val onSignedIn: () -> Unit = { navController.navigate(Routes.HOME) { popUpTo(Routes.SIGN_IN) { inclusive = true } } }
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
                                    onConnectYouTubeMusic = { authVM.connectYouTubeMusic(it, onSignedIn) },
                                    onConnectSpotifyWeb = { cookie, profile -> authVM.connectSpotifyWeb(cookie, profile, onSignedIn) },
                                    onConnectSoundCloud = { authVM.connectSoundCloud(it, onSignedIn) },
                                    onContinueAsGuest = { authVM.continueAsGuest(onSignedIn) },
                                    spotifyOperations = container.spotifyWeb.operations,
                                    onExit = if (sessionReady == true) ({
                                        authVM.reset()
                                        if (!navController.popBackStack()) navController.navigate(Routes.HOME) { popUpTo(Routes.SIGN_IN) { inclusive = true } }
                                    }) else null,
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
                                    RecapWindow.containing(period, date),
                                    onOpenInsights = { w -> navController.navigate("${Routes.INSIGHTS}/${w.period.name}/${w.start}") })
                            }
                            composable(Routes.HOME) {
                                val homeVM = viewModel(viewModelStoreOwner = rootOwner) { HomeViewModel(container) }
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
                            composable(Routes.EXPLORE) {
                                com.aurora.music.ui.screens.explore.ExploreScreen(
                                    contentPadding = inner,
                                    onOpenDetail = { kind, id -> openDetail(kind, id) },
                                    onPlayAll = { songs, index -> player.playAll(songs, index) },
                                    onOpenCategory = { navController.navigate(Routes.exploreCategory(it.params, it.title)) },
                                )
                            }
                            composable(Routes.TAG_EDIT, arguments = listOf(navArgument("songId") { defaultValue = "" })) { entry ->
                                val songId = entry.arg("songId")
                                val tagVM = viewModel(key = "tag-edit-$songId") { com.aurora.music.viewmodel.TagEditViewModel(container) }
                                LaunchedEffect(songId) { tagVM.load(songId) }
                                val tagState by tagVM.state.collectAsStateWithLifecycle()
                                com.aurora.music.ui.screens.detail.TagEditScreen(contentPadding = inner, state = tagState, onEdit = tagVM::edit, onMatch = tagVM::matchOnline,
                                    onApplyMatch = tagVM::applyMatch, onIdentify = if (container.acoustId.available && tagState.localFile) ({ tagVM.identify() }) else null,
                                    identifying = tagState.identifying, onBack = { navController.popBackStack() }, confirm = { confirm(it) })
                            }
                            composable(Routes.AI_PLAYLIST) {
                                com.aurora.music.ui.screens.aidj.AiPlaylistScreen(
                                    contentPadding = inner,
                                    current = playerState.current.takeIf { it.id.isNotEmpty() },
                                    onBack = { navController.popBackStack() },
                                    onPlay = { songs, index -> player.playAll(songs, index) },
                                    onMessage = { confirm(it) },
                                )
                            }
                            composable(Routes.RADIO) {
                                com.aurora.music.ui.screens.radio.RadioScreen(contentPadding = inner, onBack = { navController.popBackStack() }, onPlay = { player.play(it) })
                            }
                            composable(Routes.PODCASTS) {
                                com.aurora.music.ui.screens.podcasts.PodcastsScreen(contentPadding = inner, onBack = { navController.popBackStack() },
                                    onOpenPodcast = { p -> navController.navigate(Routes.podcastDetail(p.feedUrl, p.displayTitle, p.imageUrl.orEmpty(), p.author.orEmpty())) })
                            }
                            composable(Routes.PODCAST_DETAIL, arguments = listOf(navArgument("feed") { defaultValue = "" }, navArgument("title") { defaultValue = "" },
                                navArgument("image") { defaultValue = "" }, navArgument("author") { defaultValue = "" })) { entry ->
                                com.aurora.music.ui.screens.podcasts.PodcastDetailScreen(contentPadding = inner, feedUrl = entry.arg("feed"), title = entry.arg("title"),
                                    imageUrl = entry.arg("image"), author = entry.arg("author"), onBack = { navController.popBackStack() }, onPlay = { player.play(it) })
                            }
                            composable(Routes.EXPLORE_CATEGORY,
                                arguments = listOf(navArgument("params") { defaultValue = "" }, navArgument("title") { defaultValue = "" })) { entry ->
                                com.aurora.music.ui.screens.explore.ExploreCategoryScreen(
                                    contentPadding = inner,
                                    params = entry.arg("params"),
                                    title = entry.arg("title"),
                                    onBack = { navController.popBackStack() },
                                    onOpenDetail = { kind, id -> openDetail(kind, id) },
                                    onPlayAll = { songs, index -> player.playAll(songs, index) },
                                )
                            }
                            composable(Routes.SEARCH) {
                                val searchVM = viewModel(viewModelStoreOwner = rootOwner) { SearchViewModel(container) }
                                val searchState by searchVM.state.collectAsStateWithLifecycle()
                                val recentSearches by searchVM.recentSearches.collectAsStateWithLifecycle()
                                LaunchedEffect(searchState.results.songs.size) {
                                    player.checkLiked(searchState.results.songs)
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
                                val libraryVM = viewModel(viewModelStoreOwner = rootOwner) { LibraryViewModel(container) }
                                // a fresh tab visit, not a return from a pushed detail, which would reset the scrolled list
                                var visited by rememberSaveable { mutableStateOf(false) }
                                LaunchedEffect(libraryVM) { if (!visited) { visited = true; libraryVM.refreshIfStale() } }
                                val libraryState by libraryVM.state.collectAsStateWithLifecycle()
                                LaunchedEffect(libraryState.songs.size) { player.checkLiked(libraryState.songs) }
                                var librarySelection by rememberSaveable { mutableStateOf<String?>(null) }
                                if (showImport) com.aurora.music.ui.screens.library.ImportPlaylistDialog(container.playlistImporter,
                                    onOpen = { id -> openDetail("playlist", id) },
                                    onImported = { libraryVM.load(); playlistsVersion++ }, onDismiss = { showImport = false })
                                BoxWithConstraints(Modifier.fillMaxSize()) {
                                    val split = rail && maxWidth >= 960.dp
                                    val selected = librarySelection?.split(":", limit = 2)?.takeIf { split && it.size == 2 }
                                    BackHandler(enabled = split && librarySelection != null) { librarySelection = null }
                                    val listPane = rememberPaneWidth(savedLibraryWidth)
                                    val listWidth = listPane.resolve(
                                        default = if (maxWidth >= 1500.dp) 560.dp else 440.dp,
                                        min = TabletMetrics.SplitListMinWidth,
                                        max = maxWidth - TabletMetrics.SplitPaneMinWidth - TabletMetrics.DividerWidth - TabletMetrics.WindowInset,
                                    )
                                    Row(Modifier.fillMaxSize()) {
                                        Box(if (selected != null) Modifier.width(listWidth).fillMaxHeight() else Modifier.weight(1f).fillMaxHeight()) {
                                            LibraryScreen(
                                                contentPadding = inner,
                                                onOpenMix = openMix,
                                                state = libraryState,
                                                onEditTags = editTags,
                                                serverTagEditing = serverTagEditing,
                                                username = profileAppearance.name,
                                                likedIds = playerState.likedIds,
                                                currentSongId = playerState.current.id,
                                                isPlaying = playerState.isPlaying,
                                                onFilter = libraryVM::setFilter,
                                                onTabs = libraryVM::setTabs,
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
                                                playlistTargets = { repository.playlistTargets(libraryVM.scope()) },
                                                onSelectView = libraryVM::selectView,
                                                onCreatePlaylist = { name, description, target ->
                                                    playlistMutation { repository.createPlaylist(name, libraryVM.scope(), description, target) }.also { created ->
                                                        if (created) {
                                                            libraryVM.load()
                                                            playlistsVersion++
                                                        }
                                                    }
                                                },
                                                onCreateSmart = { navController.navigate(Routes.smartEdit()) },
                                                onEditSmart = { id -> navController.navigate(Routes.smartEdit(id)) },
                                                onDeleteSmart = { id -> scope.launch { store.deleteSmartPlaylist(id) } },
                                                onImportM3u = { showImport = true },
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
                                                onOpenRadio = { navController.navigate(Routes.RADIO) },
                                                onAiDj = openAiDj.takeIf { player.aiDj != null },
                                                aiDjDetail = aiDjState?.takeIf { it.active }?.let { com.aurora.music.ui.screens.aidj.aiDjLabel(it) } ?: appString(R.string.ai_dj_tile_detail),
                                                onOpenPodcasts = { navController.navigate(Routes.PODCASTS) },
                                                onOpenYouTubeDownload = { navController.navigate(Routes.YOUTUBE_DOWNLOAD) },
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
                                            PaneDivider(listPane, onSave = { scope.launch { desktopSettings.setLibraryListWidth(it) } })
                                            Box(
                                                Modifier.weight(1f).fillMaxHeight().padding(end = TabletMetrics.WindowInset)
                                                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)),
                                            ) {
                                                val paneVM = viewModel(key = "library-detail") { DetailViewModel(container) }
                                                CompositionLocalProvider(LocalPageGutter provides PageMetrics.PaneGutter) {
                                                    detailContent(selected[0], selected[1], paneVM, inner, { librarySelection = null }, { k, i ->
                                                        if (k in listOf("album", "artist", "playlist")) librarySelection = "$k:$i" else openDetail(k, i)
                                                    }) {
                                                        librarySelection = null
                                                        openDetail(selected[0], selected[1])
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
                                    folderState.content?.songs?.let { ss -> player.checkLiked(ss) }
                                }
                                FolderScreen(
                                    contentPadding = inner,
                                    title = entry.arg("title"),
                                    onEditTags = editTags,
                                    serverTagEditing = serverTagEditing,
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
                                // its own model, pinned to the library feed
                                val homeVM = viewModel(viewModelStoreOwner = rootOwner, key = "profile-home") { HomeViewModel(container) }
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
                                    { navController.popBackStack() }, { k, i -> openDetail(k, i) }, null)
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
                                val openFullPage: (String) -> Unit = remember(navController) { { route -> navController.navigate(route) } }
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
                                    val listPane = rememberPaneWidth(savedSettingsWidth)
                                    val listWidth = listPane.resolve(
                                        default = if (maxWidth >= 1300.dp) 380.dp else 320.dp,
                                        min = TabletMetrics.SettingsListMinWidth,
                                        max = minOf(TabletMetrics.SettingsListMaxWidth,
                                            maxWidth - TabletMetrics.SplitPaneMinWidth - TabletMetrics.DividerWidth - TabletMetrics.WindowInset),
                                    )
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
                                                    onOpenSonic = { open(Routes.SETTINGS_SONIC) },
                                                    onOpenSources = { open(Routes.SETTINGS_SOURCES) },
                                                    onOpenDownloads = { open(Routes.SETTINGS_STORAGE) },
                                                    onOpenAppearance = { open(Routes.SETTINGS_APPEARANCE) },
                                                    onOpenLyricsSettings = { open(Routes.SETTINGS_LYRICS) },
                                                    onOpenMerge = { open(Routes.SETTINGS_MERGE) },
                                                    onOpenLanguage = { open(Routes.SETTINGS_LANGUAGE) },
                                                    onOpenIntegrations = { open(Routes.SETTINGS_INTEGRATIONS) },
                                                    onOpenConnect = { open(Routes.SETTINGS_CONNECT) },
                                                    onOpenAbout = { open(Routes.SETTINGS_ABOUT) },
                                                    onOpenProfile = { navController.navigate(Routes.PROFILE) },
                                                    onOpenAccounts = { open(Routes.SETTINGS_ACCOUNTS) },
                                                    onOpenBackup = { open(Routes.SETTINGS_BACKUP) },
                                                    onOpenInsights = { open(Routes.SETTINGS_INSIGHTS) },
                                                    onLogout = { logout() },
                                                    onOpenRoute = { open(it) },
                                                )
                                            }
                                        }
                                        if (twoPane) {
                                            PaneDivider(listPane, onSave = { scope.launch { desktopSettings.setSettingsListWidth(it) } })
                                            Box(
                                                Modifier.weight(1f).fillMaxHeight().padding(end = TabletMetrics.WindowInset)
                                                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)),
                                            ) {
                                                CompositionLocalProvider(
                                                    LocalSettingsPaneRoots provides paneRoots,
                                                    LocalPageGutter provides PageMetrics.PaneGutter,
                                                    LocalSettingsPaneExpand provides openFullPage,
                                                ) {
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
                                ListeningHistoryScreen(contentPadding = inner, onBack = { navController.popBackStack() }, onPlay = { playById(it) },
                                    onShowOnMap = { id, at -> navController.navigate(Routes.listeningMap(id, at)) })
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
                                    onOpenDetail = { k, i -> openDetail(k, i) },
                                    onOpenInsights = { w -> navController.navigate("${Routes.INSIGHTS}/${w.period.name}/${w.start}") })
                            }
                        }
                    }
                    if (rail) {
                        AnimatedVisibility(
                            visible = shell.sidePanel,
                            enter = expandHorizontally(tween(240)) + fadeIn(tween(200)),
                            exit = shrinkHorizontally(tween(220)) + fadeOut(tween(160)),
                        ) {
                            Row(Modifier.fillMaxHeight().padding(top = panelSpacing, bottom = inner.calculateBottomPadding())) {
                                PaneDivider(panelPane, onSave = { scope.launch { desktopSettings.setSidePanelWidth(it) } }, fromEnd = true)
                                NowPlayingSidePanel(
                                    pane = sidePane ?: PlayerPane.QUEUE,
                                    onSelect = { sidePaneName = it.name },
                                    onClose = { sidePaneName = null },
                                    content = { target, modifier -> nowPlayingPane(target, modifier) },
                                    actions = { target -> nowPlayingPaneActions(target) },
                                    modifier = Modifier.width(panelWidth).fillMaxHeight().padding(end = TabletMetrics.WindowInset),
                                )
                            }
                        }
                    }
                }
            }
            if (dockVisible) DockPlayer(player) { live ->
                PlaybackDock(
                    djMode = aiDjActive,
                    state = live,
                    openPane = if (shell.sidePanel) sidePane else null,
                    volume = volume,
                    onExpand = { if (live.hasTrack) player.setExpanded(true) else showOutput = true },
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
                        .graphicsLayer { alpha = shellAlpha }
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                        .onSizeChanged { dockHeight = with(density) { it.height.toDp() } },
                )
            }

            PlayerTheme(playerState.current.artworkUrl, playerState.current.accent) {
                // registered when shown so it outranks the nav hosts yet stays below the player's own lyrics handler
                if (playerState.expanded) BackHandler { player.setExpanded(false) }
                AnimatedVisibility(
                    visible = playerState.expanded,
                    enter = slideInVertically(animationSpec = tween(320)) { it } + fadeIn(tween(220)),
                    exit = slideOutVertically(animationSpec = tween(280)) { it } + fadeOut(tween(180)),
                ) {
                    LivePlayer(player) { live -> PlayerScreen(
                        state = live,
                        onOpenMix = openMix,
                        video = player.video.collectAsStateWithLifecycle().value,
                        onRequestVideo = { canvas -> player.requestMusicVideo(canvas) { confirm(it) } },
                        onVideoVisibleChange = { player.setVideoVisible(it) },
                        onVideoQualityChange = { player.setVideoQuality(it) },
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
                        djControl = aiDjControl,
                        djStage = aiDjStage,
                        paneContent = nowPlayingPane,
                        gestures = mouseGestures,
                        requestedPane = playerPaneRequest,
                        onPaneRequestHandled = { playerPaneRequest = null },
                        paneActions = nowPlayingPaneActions,
                        onSplitChange = { split -> scope.launch { store.setTabletSetting(TabletSetting.PLAYER_SPLIT, split) } },
                        fullscreen = fullscreen,
                        onFullscreenChange = onFullscreenChange,
                        // full-screen lyrics and music videos take the whole screen too, and hand it back only if they took it
                        onImmersiveLyrics = { on ->
                            if (on && !fullscreen) {
                                lyricsFullscreen = true
                                onFullscreenChange(true)
                            } else if (!on && lyricsFullscreen) {
                                lyricsFullscreen = false
                                onFullscreenChange(false)
                            }
                        },
                    ) }
                }
            }
            // the shell's snackbar sits under the expanded player, so player messages need their own host above it
            if (playerState.expanded) SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))

            AnimatedVisibility(visible = showVisualizer, enter = fadeIn(tween(220)), exit = fadeOut(tween(180))) {
                LivePlayer(player) { live -> VisualizerScreen(state = live, onClose = { showVisualizer = false }) }
            }
            if (showMix) {
                Box(Modifier.fillMaxSize().background(com.aurora.music.ui.theme.solidColors().background)) {
                    com.aurora.music.ui.screens.player.MixScreen(mixVM, mixQueue, onClose = { showMix = false })
                }
            }
            val tour = whatsNew?.let { versions -> WhatsNewCatalog.releases.filter { it.version in versions }.reversed() }
            if (tour != null) {
                val whatsNewEnabled by store.whatsNewEnabled.collectAsStateWithLifecycle(initialValue = true)
                when {
                    tour.isEmpty() -> LaunchedEffect(Unit) { closeWhatsNew() }
                    whatsNewTrying -> WhatsNewReturnPill(onResume = { whatsNewPage++; whatsNewTrying = false }, onClose = ::closeWhatsNew)
                    else -> AnimatedVisibility(
                        visibleState = remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } },
                        enter = fadeIn(tween(260)),
                    ) {
                        WhatsNewTour(
                            releases = tour,
                            page = whatsNewPage,
                            onPageChange = { whatsNewPage = it },
                            enabled = whatsNewEnabled,
                            onEnabledChange = { enabled -> scope.launch { store.setWhatsNewEnabled(enabled) } },
                            onTry = { route ->
                                whatsNewTrying = true
                                player.setExpanded(false)
                                navController.navigate(route) { launchSingleTop = true }
                            },
                            onClose = ::closeWhatsNew,
                        )
                    }
                }
            }
        }
    }
    }

    if (showSpeedSheet) {
        SpeedPitchSheet(
            speed = playerState.speed,
            onSpeed = { player.setSpeed(it) },
            onReset = { player.resetSpeedPitch() },
            onOpenReverbSettings = {
                showSpeedSheet = false
                player.setExpanded(false)
                navController.navigate(Routes.SETTINGS_REVERB) { launchSingleTop = true }
            },
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
            exclusiveAvailable = player.exclusiveAvailable,
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

private fun NavLayout.ported() = NavLayout(main.filterNot { it.route in unportedRoutes }, more.filterNot { it.route in unportedRoutes }, hidden)

private fun NavBackStackEntry.arg(name: String): String = arguments?.read { getStringOrNull(name) }.orEmpty()

@Composable
private fun DownloadProgressBanner(count: Int) {
    val states by LocalDesktopContainer.current.downloadManager.states.collectAsStateWithLifecycle()
    // chunks land every 250 ms per download, so the bar steps rather than restarting a tween each time
    val progress = states.values.mapNotNull { (it as? DownloadState.Downloading)?.progress }
        .let { if (it.isEmpty()) 0f else it.sum() / count.coerceAtLeast(1) }.coerceIn(0f, 1f)
    val classic = LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (classic) Modifier.clip(RoundedCornerShape(themedCorner(18.dp)))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.fade(0.96f))
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(themedCorner(18.dp)))
                .glassEdge(RoundedCornerShape(themedCorner(18.dp)))
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
                progress = { progress },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}

private suspend fun playlistMutation(block: suspend () -> Boolean): Boolean = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    false
}

private const val SEEK_STEP_SECONDS = 5
private const val VOLUME_STEP = 0.1f
private const val KEY_SEEK_WINDOW_MS = 1_000L
private const val KEY_REPEAT_GAP_MS = 150L
private const val KEY_SEEK_SEND_MS = 100L
private const val SCRUB_SECONDS_PER_SECOND = 12f

// position moves ten times a second, so only the views that show it read the live state
@Composable
private fun LivePlayer(player: PlayerController, content: @Composable (PlayerUiState) -> Unit) {
    val state by player.state.collectAsStateWithLifecycle()
    content(state)
}

// the dock bar moves under a pixel per tick, so it follows the position in half seconds
@Composable
private fun DockPlayer(player: PlayerController, content: @Composable (PlayerUiState) -> Unit) {
    val state by remember(player) {
        player.state.map { it.copy(positionSec = floor(it.positionSec * 2) / 2) }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(player.state.value)
    content(state)
}
