package com.aurora.music.ui

import androidx.compose.material.icons.outlined.Check
import com.aurora.music.ui.theme.paperClip
import com.aurora.music.ui.components.longPressFirst
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.MoreHoriz
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
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
import com.aurora.music.ui.theme.liquidGlass
import com.kyant.backdrop.backdrops.layerBackdrop
import com.aurora.music.ui.theme.rememberPlayerColorScheme
import com.aurora.music.ui.screens.settings.PlaybackSettingsScreen
import com.aurora.music.ui.screens.settings.SettingsScreen
import com.aurora.music.ui.onboarding.AuroraOnboarding
import com.aurora.music.ui.onboarding.ListeningMode
import com.aurora.music.ui.onboarding.SetupPhase
import com.aurora.music.ui.onboarding.SetupSource
import com.aurora.music.ui.onboarding.SetupTask
import com.aurora.music.ui.onboarding.rememberOnboardingState
import com.aurora.music.ui.whatsnew.WhatsNewCatalog
import com.aurora.music.ui.whatsnew.WhatsNewLauncher
import com.aurora.music.ui.whatsnew.WhatsNewReturnPill
import com.aurora.music.ui.whatsnew.WhatsNewTour
import com.aurora.music.viewmodel.AuthViewModel
import com.aurora.music.viewmodel.DetailViewModel
import com.aurora.music.viewmodel.HomeViewModel
import com.aurora.music.viewmodel.LibraryViewModel
import com.aurora.music.viewmodel.PlayerViewModel
import com.aurora.music.viewmodel.SearchViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import com.aurora.music.model.accent

private const val LIKES_RESUME_INTERVAL_MS = 5 * 60_000L

internal fun likesResumeDue(pulledAt: Long?, now: Long, sessionReady: Boolean?, offline: Boolean): Boolean =
    sessionReady == true && !offline && pulledAt?.let { now - it < LIKES_RESUME_INTERVAL_MS } != true

@Composable
fun AuroraApp() {
    val context = LocalContext.current
    val imeInsets = WindowInsets.ime.asPaddingValues()
    // only the crossing recomposes, not every frame of the keyboard animation
    val imeVisible by remember(imeInsets) { derivedStateOf { imeInsets.calculateBottomPadding() > 120.dp } }
    val container = (context.applicationContext as AuroraApplication).container

    val navController = rememberNavController()
    val playerVM: PlayerViewModel = viewModel()
    val activityOwner = checkNotNull(androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner.current)
    val authVM: AuthViewModel = viewModel()

    val playerState by playerVM.state.collectAsStateWithLifecycle()
    val authState by authVM.state.collectAsStateWithLifecycle()
    val sessionReady by container.sessionReady.collectAsStateWithLifecycle()
    com.aurora.music.ui.screens.auth.SoundCloudCaptchaDialog(container.soundcloudWriter)
    val onboarding = rememberOnboardingState()
    // versions in the open what's new tour, the page it's on, and whether "Try it" has the real screen up
    var whatsNew by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var whatsNewPage by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    var whatsNewTrying by rememberSaveable { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(container) {
        if (onboarding.phase == SetupPhase.HIDDEN && container.settingsStore.onboardingSeen.first() == null) {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (packageInfo.firstInstallTime == packageInfo.lastUpdateTime) {
                onboarding.restart()
                navController.navigate(Routes.SETUP) { launchSingleTop = true }
            }
            else container.settingsStore.setOnboardingSeen()
        }
        if (whatsNew == null) {
            val store = container.settingsStore
            val current = com.aurora.music.BuildConfig.VERSION_NAME
            val lastSeen = store.whatsNewSeen.first()
            val unseen = com.aurora.music.data.WhatsNew.unseen(WhatsNewCatalog.releases, lastSeen, current) { it.version }
            when (com.aurora.music.data.WhatsNew.onLaunch(onboarding.phase != SetupPhase.HIDDEN, store.whatsNewEnabled.first(),
                lastSeen, current, unseen.isNotEmpty())) {
                com.aurora.music.data.WhatsNew.Launch.SHOW -> { whatsNew = unseen.map { it.version }; whatsNewPage = 0 }
                com.aurora.music.data.WhatsNew.Launch.MARK_SEEN -> store.setWhatsNewSeen(current)
                com.aurora.music.data.WhatsNew.Launch.NOTHING -> Unit
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        WhatsNewLauncher.opened.collect { version ->
            whatsNew = listOf(version)
            whatsNewPage = 0
            whatsNewTrying = false
        }
    }
    // a newer release on GitHub gets one toast at launch; the same release never comes up again
    androidx.compose.runtime.LaunchedEffect(container) {
        val updater = container.appUpdater
        updater.checkForUpdate()
        val checked = kotlinx.coroutines.withTimeoutOrNull(60_000) {
            updater.state.first { !it.checking && (it.checked || it.error != null) }
        } ?: return@LaunchedEffect
        val tag = checked.release?.tag
        if (com.aurora.music.data.updates.UpdateNotice.shouldAnnounce(checked.updateAvailable, tag,
                container.settingsStore.updateAnnounced.first()) && tag != null) {
            container.settingsStore.setUpdateAnnounced(tag)
            android.widget.Toast.makeText(context, appString(R.string.update_available_toast,
                com.aurora.music.data.WhatsNew.shortVersion(tag)), android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val session by container.session.collectAsStateWithLifecycle()
    val savedSessions by container.savedSessions.collectAsStateWithLifecycle()
    val downloadsMap by container.downloadManager.downloads.collectAsStateWithLifecycle()
    val localMode = session?.type == com.aurora.music.data.ServerType.LOCAL
    val localAppearance by container.localProfileAppearance.collectAsStateWithLifecycle()
    val profileAppearance = remember(session, localAppearance) { com.aurora.music.data.profileAppearance(session, localAppearance) }
    val serverTagEditing = session?.let { container.repository.supportsServerTagEdit } ?: false
    // pins scoped to the active connection
    val currentServer = session?.server ?: ""
    val allPins by container.pins.collectAsStateWithLifecycle()
    val pins = remember(allPins, currentServer) { allPins.filter { it.serverId == currentServer } }
    val gesturePrefs by container.gesturePrefs.collectAsStateWithLifecycle()
    val offlineMode by container.offline.collectAsStateWithLifecycle()
    val downloadAccountEpoch by container.accountEpoch.collectAsStateWithLifecycle()
    val downloadLibraryEpoch by container.libraryReload.collectAsStateWithLifecycle()
    val downloadedIds by androidx.compose.runtime.produceState(emptySet<String>(), downloadsMap, session, sessionReady, offlineMode, downloadAccountEpoch, downloadLibraryEpoch) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { container.repository.downloadedIds() }
    }

    // only the count lives at the root; per-chunk progress recomposes just the banner
    val activeDownloads by remember(container) {
        container.downloadManager.states.map { states ->
            states.values.count { it is com.aurora.music.data.DownloadState.Queued || it is com.aurora.music.data.DownloadState.Downloading }
        }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = 0)

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
    var matchSong by remember { mutableStateOf<com.aurora.music.model.Song?>(null) }
    val hiddenSongs by container.trackChoices.hidden.collectAsStateWithLifecycle()

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

    // stars from other devices re-pull on foreground at most every 5 min, never offline or before sign-in
    var likesPulledAt by remember { mutableStateOf<Long?>(null) }
    fun pullLikes(restart: Boolean = false) { likesPulledAt = android.os.SystemClock.elapsedRealtime(); playerVM.refreshLikes(restart) }
    // the player pulls on sign-in itself, so that counts as the latest pull
    androidx.compose.runtime.LaunchedEffect(sessionReady) {
        if (sessionReady == true && !container.offline.value) likesPulledAt = android.os.SystemClock.elapsedRealtime()
    }
    // a unified or merge toggle renames every id, so its hearts can't wait for the window
    androidx.compose.runtime.LaunchedEffect(container) {
        container.libraryReload.drop(1).collect {
            likesPulledAt = null
            if (container.sessionReady.value == true && !container.offline.value) pullLikes(restart = true)
        }
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                if (likesResumeDue(likesPulledAt, android.os.SystemClock.elapsedRealtime(), container.sessionReady.value, container.offline.value)) pullLikes()
                container.networkOutput.restoreReceiver()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // announce when a batch finishes active falls back to zero
    var hadActiveDownloads by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(activeDownloads) {
        if (activeDownloads > 0) {
            hadActiveDownloads = true
        } else if (hadActiveDownloads) {
            hadActiveDownloads = false
            val failed = container.downloadManager.states.value.values.count { it is com.aurora.music.data.DownloadState.Failed }
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
    val navRoute = if (currentRoute == Routes.EXPLORE_CATEGORY) Routes.EXPLORE else currentRoute
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
    val exploreAvailable = remember(sessionReady, downloadAccountEpoch) { container.repository.supportsExplore }
    val aiDjActive = playerVM.aiDj.state.collectAsStateWithLifecycle().value.active
    val playerPanes = (if (exploreAvailable) PlayerPane.entries else listOf(PlayerPane.LYRICS, PlayerPane.QUEUE)).let { if (aiDjActive) it - PlayerPane.QUEUE else it }
    val sidePane = sidePaneName?.let { runCatching { PlayerPane.valueOf(it) }.getOrNull() }?.takeIf { it in playerPanes }
    val shell = windowLayout.shell(panelRequested = rail && sidePane != null && playerState.hasTrack)
    val uiPrefs = com.aurora.music.ui.theme.LocalUiPrefs.current
    val simpleMode by container.simpleMode.collectAsStateWithLifecycle()
    val navLayout = remember(uiPrefs.navLayout, simpleMode, exploreAvailable) {
        com.aurora.music.navigation.NavMenu.parse(uiPrefs.navLayout).visible(simpleMode, discovery = exploreAvailable)
    }
    val navGap = if (rail) uiPrefs.tabletNavGap.dp else 0.dp
    val barMargin = if (uiPrefs.themeStyle == com.aurora.music.data.ThemeStyle.GLASS) 16.dp else 10.dp
    val pageMargin = if (rail) uiPrefs.tabletPageMargin.dp else 0.dp
    val panelSpacing = uiPrefs.tabletPanelSpacing.dp.coerceAtLeast(4.dp)
    val density = androidx.compose.ui.platform.LocalDensity.current
    var dockHeight by remember { mutableStateOf(0.dp) }
    val dockVisible = showChrome && rail && playerState.showsBar && !imeVisible
    val pageWidth = when {
        currentRoute == Routes.SIGN_IN || currentRoute == Routes.SETUP -> androidx.compose.ui.unit.Dp.Unspecified
        currentRoute in topLevelDestinations.map { it.route } -> 1280.dp
        currentRoute == Routes.SETTINGS && windowLayout.useNavigationRail -> 1280.dp
        currentRoute?.startsWith("settings") == true -> 840.dp
        else -> 960.dp
    }

    var showSpeedSheet by rememberSaveable { mutableStateOf(false) }
    var showQueue by rememberSaveable { mutableStateOf(false) }
    var showRelated by rememberSaveable { mutableStateOf(false) }
    var showMix by remember { mutableStateOf(false) }
    var mixRequest by remember { mutableStateOf<com.aurora.music.mix.MixCollectionRequest?>(null) }
    var mixQueue by remember { mutableStateOf<List<com.aurora.music.model.Song>>(emptyList()) }
    var showVisualizer by rememberSaveable { mutableStateOf(false) }
    var showOutput by rememberSaveable { mutableStateOf(false) }
    var showSleep by rememberSaveable { mutableStateOf(false) }
    var showNavMore by remember { mutableStateOf(false) }
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
    // only the message and error reach the root; mix playback publishes every frame
    val mixMessage by remember(mixVM) { mixVM.state.map { it.message }.distinctUntilChanged() }.collectAsStateWithLifecycle(initialValue = null)
    val mixError by remember(mixVM) { mixVM.playback.map { it.error }.distinctUntilChanged() }.collectAsStateWithLifecycle(initialValue = null)
    androidx.compose.runtime.LaunchedEffect(mixRequest?.token) {
        mixRequest?.let { request ->
            mixVM.mixCollection(request.kind, request.id, request.name, playInPlayer = true) {
                playerVM.setExpanded(true)
            }
            mixRequest = null
        }
    }
    androidx.compose.runtime.LaunchedEffect(mixMessage, showMix) {
        if (!showMix) mixMessage?.let { confirm(it); mixVM.dismissMessage() }
    }
    androidx.compose.runtime.LaunchedEffect(mixError) {
        if (!showMix) mixError?.let { confirm(it) }
    }

    val playerColors = rememberPlayerColorScheme(playerState.current.artworkUrl, playerState.current.accent)
    // the opaque expanded player hides the page and glass chrome so they stop rendering under it
    val playerTransition = androidx.compose.animation.core.updateTransition(playerState.expanded, label = "player")
    val playerSurfaceOffset = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val hiddenBehindPlayer = Modifier.graphicsLayer {
        val covered = playerTransition.currentState && playerTransition.targetState && !playerTransition.isRunning &&
            playerSurfaceOffset.floatValue == 0f
        alpha = if (covered) 0f else 1f
    }

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

    var homeFeedMenu by remember { mutableStateOf<Pair<List<com.aurora.music.data.HomeFeedChoice>, String>?>(null) }
    val voiceSearch = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            androidx.lifecycle.ViewModelProvider(activityOwner)[SearchViewModel::class.java].voiceSearch(spoken)
            navigateTopLevel(Routes.SEARCH)
        }
    }
    // holding search goes straight to speech, holding home picks which merged feed opens
    fun onNavLongPress(route: String) {
        when (route) {
            Routes.SEARCH -> {
                container.haptic()
                val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, appString(R.string.text_speak_to_search_91f0aa))
                }
                runCatching { voiceSearch.launch(intent) }.onFailure { navigateTopLevel(Routes.SEARCH) }
            }
            Routes.HOME -> {
                val home = androidx.lifecycle.ViewModelProvider(activityOwner)[HomeViewModel::class.java].state.value
                if (home.feeds.size > 1) { container.haptic(); homeFeedMenu = home.feeds to home.selectedFeed }
            }
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

    val aiDjState by playerVM.aiDj.state.collectAsStateWithLifecycle()
    val openAiDj: () -> Unit = { if (aiDjState.active) playerVM.setExpanded(true) else playerVM.startAiDj { confirm(it) } }
    androidx.compose.runtime.LaunchedEffect(aiDjState.active) { if (aiDjState.active) showQueue = false }
    var showAiDjSheet by remember { mutableStateOf(false) }
    val aiDjSpoken by container.settingsStore.aiDjSpoken.collectAsStateWithLifecycle(true)
    val aiDjMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) confirm(com.aurora.music.localization.appString(R.string.ai_dj_mic_denied))
    }
    val holdAiDj: () -> Unit = {
        when {
            container.aiDjUnsupported != null -> confirm(com.aurora.music.localization.appString(R.string.ai_dj_model_unsupported))
            container.aiDjVoiceModels.model() == null -> showAiDjSheet = true
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED ->
                aiDjMic.launch(android.Manifest.permission.RECORD_AUDIO)
            !playerVM.listenAiDj() -> confirm(com.aurora.music.localization.appString(R.string.ai_dj_mic_denied))
        }
    }
    val aiDjControl: (@Composable () -> Unit)? = if (aiDjState.active) ({
        com.aurora.music.ui.screens.aidj.AiDjPlayerButton(aiDjState, { showAiDjSheet = true }, holdAiDj, playerVM::hearAiDj)
    }) else null
    val aiDjStage: (@Composable (Modifier) -> Unit)? = if (aiDjState.speech != null || aiDjState.listening || aiDjState.thinking) ({ modifier ->
        com.aurora.music.ui.screens.aidj.AiDjStage(aiDjState, modifier)
    }) else null
    androidx.compose.runtime.LaunchedEffect(aiDjState.missed) { if (aiDjState.missed) confirm(com.aurora.music.localization.appString(R.string.ai_dj_missed)) }
    if (showAiDjSheet && aiDjState.active) com.aurora.music.ui.screens.aidj.AiDjSheet(
        state = aiDjState,
        spoken = aiDjSpoken,
        speakerModels = container.aiDjSpeakerModels.takeIf {
            container.aiDjUnsupported == null && com.aurora.music.ai.speakerLanguage(com.aurora.music.localization.AppStrings.context.resources.configuration.locales[0].language) != null
        },
        voiceModels = container.aiDjVoiceModels.takeIf { container.aiDjUnsupported == null },
        onSteer = playerVM::steerAiDj,
        onSwitch = playerVM::switchAiDj,
        onStop = playerVM::stopAiDj,
        onSpoken = { scope.launch { container.settingsStore.setAiDjSpoken(it) } },
        onDownloadSpeaker = container::downloadAiDjSpeakerModel,
        onDownloadVoice = container::downloadAiDjVoiceModel,
        onDismiss = { showAiDjSheet = false },
    )
    val nowPlayingPane: @Composable (PlayerPane, Modifier) -> Unit = { pane, modifier ->
        when (pane) {
            PlayerPane.LYRICS -> LyricsPane(playerState, onSeek = { playerVM.seekTo(it) }, modifier = modifier.paperClip(RoundedCornerShape(20.dp)))
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
            PlayerPane.RELATED -> com.aurora.music.ui.screens.player.RelatedPane(
                playerState.current,
                onOpenDetail = { kind, id -> playerVM.setExpanded(false); openDetail(kind, id) },
                onPlayAll = { songs, index -> playerVM.playAll(songs, index) },
                modifier = modifier,
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
                detailState.data?.tracks?.let { ts -> playerVM.checkLikedList(ts) }
            }
            DetailScreen(
                contentPadding = padding,
                state = detailState,
                collectionKey = "$kind:$id",
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
                hiddenIds = if (kind == "playlist") hiddenSongs[com.aurora.music.data.TrackChoices.collectionKey(kind, id)].orEmpty() else emptySet(),
                onToggleHidden = if (kind == "playlist") ({ song -> container.trackChoices.setHidden(kind, id, song.id, !container.trackChoices.isHidden(kind, id, song.id)) }) else null,
                isItemLiked = playerState.likedIds.contains(id),
                onToggleItemLike = { playerVM.toggleLike(id, kind) },
                canLikeItem = container.repository.canLike(id),
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
                onVisibleSongs = playerVM::checkVisibleLiked,
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
                    com.aurora.music.ui.screens.settings.NetworkOutputScreen(padding, onBack = { nav.popBackStack() },
                        onOpenConnect = { nav.navigate(Routes.SETTINGS_CONNECT) })
                }
                composable(Routes.SETTINGS_CONNECT) {
                    com.aurora.music.ui.screens.settings.ConnectSettingsScreen(padding) { nav.popBackStack() }
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
                composable(Routes.SETTINGS_WHATS_NEW) {
                    com.aurora.music.ui.screens.settings.WhatsNewSettingsScreen(padding, onBack = { nav.popBackStack() })
                }
                composable(Routes.SETTINGS_INSIGHTS) {
                    com.aurora.music.ui.screens.settings.DetailedStatsScreen(contentPadding = padding, onBack = { nav.popBackStack() },
                        onOpenInsights = { nav.navigate(Routes.INSIGHTS) }, onOpenMap = { nav.navigate(Routes.LISTENING_MAP) })
                }
                composable(Routes.INSIGHTS) {
                    com.aurora.music.ui.screens.stats.InsightsScreen(padding, onBack = { nav.popBackStack() }, onSetup = { nav.navigate(Routes.SETTINGS_INSIGHTS) },
                        onOpenMap = { nav.navigate(Routes.LISTENING_MAP) })
                }
                composable(Routes.LISTENING_MAP) {
                    com.aurora.music.ui.screens.stats.ListeningMapScreen(padding, onBack = { nav.popBackStack() })
                }
                composable(Routes.LISTENING_MAP + "/{songId}/{at}") { entry ->
                    com.aurora.music.ui.screens.stats.ListeningMapScreen(padding, onBack = { nav.popBackStack() },
                        songId = entry.arguments?.getString("songId"), at = entry.arguments?.getString("at")?.toLongOrNull())
                }
                composable(Routes.INSIGHTS + "/{period}/{date}") { entry ->
                    val period = runCatching { com.aurora.music.data.RecapPeriod.valueOf(entry.arguments?.getString("period").orEmpty()) }.getOrDefault(com.aurora.music.data.RecapPeriod.MONTH)
                    val date = runCatching { java.time.LocalDate.parse(entry.arguments?.getString("date")) }.getOrDefault(java.time.LocalDate.now())
                    com.aurora.music.ui.screens.stats.InsightsScreen(padding, onBack = { nav.popBackStack() }, onSetup = { nav.navigate(Routes.SETTINGS_INSIGHTS) },
                        initialWindow = com.aurora.music.data.RecapWindow.containing(period, date), onOpenMap = { nav.navigate(Routes.LISTENING_MAP) })
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
                        onOpenStorage = { nav.navigate(Routes.SETTINGS_STORAGE) { launchSingleTop = true } },
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
                        onOpenReverb = { nav.navigate(Routes.SETTINGS_REVERB) },
                    )
                }
                composable(Routes.SETTINGS_REVERB) {
                    com.aurora.music.ui.screens.settings.ReverbSettingsScreen(contentPadding = padding, onBack = { nav.popBackStack() })
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
                composable(Routes.SETTINGS_LYRICS) {
                    com.aurora.music.ui.screens.settings.LyricsSettingsScreen(contentPadding = padding, onBack = { nav.popBackStack() })
                }
                composable(Routes.SETTINGS_MERGE) {
                    com.aurora.music.ui.screens.settings.MergeOptionsScreen(contentPadding = padding, onBack = { nav.popBackStack() })
                }
                composable(Routes.SETTINGS_ARTIST_SEPARATORS) {
                    com.aurora.music.ui.screens.settings.ArtistSeparatorsScreen(contentPadding = padding, onBack = { nav.popBackStack() })
                }
                composable(Routes.SETTINGS_PERMISSIONS) {
                    com.aurora.music.ui.screens.settings.PermissionsScreen(contentPadding = padding, onBack = { nav.popBackStack() })
                }
                composable(Routes.SETTINGS_STORAGE) {
                    com.aurora.music.ui.screens.settings.StorageSettingsScreen(contentPadding = padding, onBack = { nav.popBackStack() },
                        onOpenYouTubeDownload = { nav.navigate(Routes.YOUTUBE_DOWNLOAD) })
                }
                composable(Routes.YOUTUBE_DOWNLOAD) {
                    com.aurora.music.ui.screens.youtube.YouTubeDownloadScreen(contentPadding = padding, onBack = { nav.popBackStack() },
                        onOpenSettings = { nav.navigate(Routes.SETTINGS_STORAGE) { launchSingleTop = true } })
                }
                composable(Routes.SETTINGS_ABOUT) {
                    com.aurora.music.ui.screens.settings.AboutSettingsScreen(contentPadding = padding, onBack = { nav.popBackStack() })
                }
                composable(Routes.SETTINGS_LANGUAGE) {
                    com.aurora.music.ui.screens.settings.LanguageSettingsScreen(contentPadding = padding, onBack = { nav.popBackStack() })
                }
        }

    androidx.compose.runtime.CompositionLocalProvider(
        com.aurora.music.ui.screens.player.LocalPlayerPanes provides playerPanes,
        com.aurora.music.ui.components.LocalStartRadio provides remember(playerVM) { { song: com.aurora.music.model.Song -> playerVM.startSonicRadio(song) { confirm(it) } } },
        com.aurora.music.ui.components.LocalCanLike provides container.repository::canLike,
        com.aurora.music.ui.components.LocalLikeMenu provides remember(playerVM) { com.aurora.music.ui.components.LikeMenu(playerVM::likeOptions, playerVM::setLikedOn, playerVM::likedSources) },
        com.aurora.music.ui.components.LocalRepostMenu provides remember(container) { com.aurora.music.ui.components.RepostMenu(container.repository, scope, ::confirm) },
        com.aurora.music.ui.components.LocalMatchPicker provides remember { { song: com.aurora.music.model.Song -> matchSong = song } },
    ) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen || drawerState.targetValue == DrawerValue.Open ||
            (showChrome && onTopLevel && !(rail && shell.expandedSidebar)),
        drawerContent = {
            // the same themed backdrop as the pages, so the drawer carries the theme's glow, lines or streaks
            ModalDrawerSheet(drawerState = drawerState, drawerContainerColor = Color.Transparent) {
                Box(Modifier.fillMaxHeight()) {
                AmbientBackground(Modifier.matchParentSize())
                SidebarContent(
                    username = profileAppearance.name,
                    server = session?.server ?: "",
                    avatarUrl = profileAppearance.avatarUrl,
                    layout = navLayout,
                    currentRoute = navRoute,
                    onProfile = { closeDrawerThen { navController.navigate(Routes.PROFILE) } },
                    onSettings = { closeDrawerThen { navController.navigate(Routes.SETTINGS) } },
                    onOpen = { item -> closeDrawerThen { openNavItem(item) } },
                    onLogout = { closeDrawerThen { logout() } },
                )
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            val glassLayers = com.aurora.music.ui.theme.LocalGlassLayers.current
            AmbientBackground(glassLayers?.let { Modifier.layerBackdrop(it.ambient) } ?: Modifier)
            if (rail) {
                Box(
                    hiddenBehindPlayer.fillMaxHeight().then(
                        if (dockVisible) Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(androidx.compose.foundation.layout.WindowInsetsSides.Bottom))
                            .padding(bottom = dockHeight + 12.dp) else Modifier
                    ),
                ) {
                if (shell.expandedSidebar) {
                    TabletSidebar(
                        currentRoute = navRoute,
                        username = profileAppearance.name,
                        server = session?.server ?: "",
                        avatarUrl = profileAppearance.avatarUrl,
                        layout = navLayout,
                        onOpen = { openNavItem(it) },
                        onProfile = { navController.navigate(Routes.PROFILE) { launchSingleTop = true } },
                        onSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                    )
                } else {
                    TabletNavigationRail(navRoute, navLayout.main, { openNavItem(it) }, { openDrawer() }) {
                        navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                    }
                }
                }
            }
            Scaffold(
                modifier = hiddenBehindPlayer.padding(start = if (rail) shell.navWidth + navGap else 0.dp).then(
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
                                .padding(start = if (rail) pageMargin else barMargin, end = if (rail) pageMargin.coerceAtLeast(12.dp) else barMargin,
                                    top = 8.dp, bottom = if (dockVisible) 0.dp else if (rail) 12.dp else 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (activeDownloads > 0) {
                                DownloadProgressBanner(count = activeDownloads)
                                Spacer(Modifier.height(8.dp))
                            }
                            if (offlineMode) {
                                Row(
                                    Modifier.paperClip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)).padding(horizontal = 14.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Filled.CloudOff, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(appString(R.string.text_offline_mode_66cf31), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                            if (playerState.showsBar && !rail) {
                                // The mini player belongs to the app chrome, so it uses the app palette.
                                MiniPlayer(
                                    state = playerState,
                                    onExpand = { if (!playerState.hasTrack) showOutput = true else if (playerState.current.id.startsWith("aurora-mix:")) showMix = true else playerVM.setExpanded(true) },
                                    onTogglePlay = { playerVM.togglePlay() },
                                    onToggleLike = { playerVM.toggleLikeCurrent() },
                                    onNext = { playerVM.next() },
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            if (!rail) Box {
                                FloatingNav(navRoute, navLayout.bar, navLayout.barMore,
                                    onNavigate = { navigateTopLevel(it) }, onMore = { showNavMore = true }, onLongPress = ::onNavLongPress)
                                Box(Modifier.align(Alignment.TopStart).padding(start = 8.dp)) {
                                    androidx.compose.material3.DropdownMenu(expanded = homeFeedMenu != null, onDismissRequest = { homeFeedMenu = null },
                                        shape = com.aurora.music.ui.theme.paperSquare(RoundedCornerShape(18.dp))) {
                                        val (feeds, selected) = homeFeedMenu ?: (emptyList<com.aurora.music.data.HomeFeedChoice>() to "")
                                        feeds.forEach { feed ->
                                            androidx.compose.material3.DropdownMenuItem(
                                                leadingIcon = { Icon(com.aurora.music.ui.components.sourceViewIcon(feed.id), null) },
                                                text = { Text(feed.label, fontWeight = if (feed.id == selected) FontWeight.Bold else FontWeight.Medium) },
                                                trailingIcon = { if (feed.id == selected) Icon(androidx.compose.material.icons.Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary) },
                                                onClick = {
                                                    homeFeedMenu = null
                                                    androidx.lifecycle.ViewModelProvider(activityOwner)[HomeViewModel::class.java].selectFeed(feed.id)
                                                    navigateTopLevel(Routes.HOME)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        }
                    }
                },
            ) { inner ->
                Row(Modifier.fillMaxSize().then(glassLayers?.let { Modifier.layerBackdrop(it.page) } ?: Modifier)) {
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
                            onConnectSpotifyWeb = authVM::connectSpotifyWeb,
                            onConnectYouTubeMusic = authVM::connectYouTubeMusic,
                            onConnectSoundCloud = authVM::connectSoundCloud,
                            onContinueAsGuest = authVM::continueAsGuest,
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
                        com.aurora.music.ui.screens.stats.ListeningStatsScreen(inner, { navController.popBackStack() }, { playById(it) }, { k, i -> openDetail(k, i) }, com.aurora.music.data.RecapWindow.containing(period, date),
                            onOpenInsights = { w -> navController.navigate("${Routes.INSIGHTS}/${w.period.name}/${w.start}") })
                    }
                    composable(Routes.HOME) {
                        val homeVM: HomeViewModel = viewModel(viewModelStoreOwner = activityOwner)
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
                        androidx.compose.runtime.LaunchedEffect(searchState.results.songs) {
                            playerVM.checkLiked(searchState.results.songs)
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
                        // activity scoped so switching tabs doesn't refetch and rebuild the whole library
                        val libraryVM: LibraryViewModel = viewModel(viewModelStoreOwner = activityOwner)
                        val libraryState by libraryVM.state.collectAsStateWithLifecycle()
                        androidx.compose.runtime.LaunchedEffect(libraryState.songs.size) {
                            playerVM.checkLikedList(libraryState.songs)
                        }
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
                        var showImport by rememberSaveable { mutableStateOf(false) }
                        if (showImport) com.aurora.music.ui.screens.library.ImportPlaylistSheet(container.playlistImporter,
                            onOpen = { id -> navController.navigate(Routes.detail("playlist", id)) },
                            onImported = { libraryVM.load() }, onDismiss = { showImport = false })
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
                                onCycleView = libraryVM::cycleView,
                                username = profileAppearance.name,
                                likedIds = playerState.likedIds,
                                currentSongId = playerState.current.id,
                                isPlaying = playerState.isPlaying,
                                onFilter = libraryVM::setFilter,
                                onTabs = libraryVM::setTabs,
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
                                onCreatePlaylist = { name, description, target ->
                                    playlistMutation { container.repository.createPlaylist(name, libraryVM.scope(), description, target) }.also { created ->
                                        if (created) libraryVM.load()
                                    }
                                },
                                playlistTargets = remember(libraryState.view, libraryState.views, libraryState.loading) { container.repository.playlistTargets(libraryVM.scope()) },
                                playlistHome = session?.typeLabel?.localizedMediaType() ?: "",
                                onCreateSmart = { navController.navigate(Routes.smartEdit()) },
                                onEditSmart = { id -> navController.navigate(Routes.smartEdit(id)) },
                                onDeleteSmart = { id -> scope.launch { container.settingsStore.deleteSmartPlaylist(id) } },
                                onImportM3u = { showImport = true },
                                onCreateWithAi = { navController.navigate(Routes.AI_PLAYLIST) { launchSingleTop = true } },
                                onAiDj = openAiDj,
                                aiDjDetail = if (aiDjState.active) com.aurora.music.ui.screens.aidj.aiDjLabel(aiDjState) else com.aurora.music.localization.appString(R.string.ai_dj_tile_detail),
                                onExportPlaylist = { id, kind, title -> scope.launch {
                                    val text = container.repository.exportPlaylist(kind, id)
                                    if (text == null) confirm(appString(R.string.text_nothing_to_export_7faf33)) else {
                                        pendingM3u = text
                                        exportM3uLauncher.launch("$title.m3u8")
                                    }
                                } },
                                onOpenFolders = { navController.navigate(Routes.folders()) },
                                onOpenRadio = { navController.navigate(Routes.RADIO) },
                                onOpenYouTubeDownload = { navController.navigate(Routes.YOUTUBE_DOWNLOAD) },
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
                                onVisibleSongs = playerVM::checkVisibleLiked,
                                selectedItem = if (split) librarySelection else null,
                            )
                            }
                            val selected = librarySelection?.split(":", limit = 2)
                            if (split && selected != null && selected.size == 2) {
                                Spacer(Modifier.width(16.dp))
                                Box(
                                    Modifier.weight(1.1f).fillMaxHeight().padding(top = 8.dp)
                                        .paperClip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
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
                            folderState.content?.songs?.let { ss -> playerVM.checkLiked(ss) }
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
                        val homeVM: HomeViewModel = viewModel(viewModelStoreOwner = activityOwner)
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
                            if (!twoPane && paneTouched && navController.currentDestination?.route == Routes.SETTINGS) {
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
                                    onOpenConnect = { open(Routes.SETTINGS_CONNECT) },
                                    onOpenLoudness = { open(Routes.SETTINGS_LOUDNESS) },
                                    onOpenAdvancedAudio = { open(Routes.SETTINGS_ADVANCED_AUDIO) },
                                    onOpenAlarm = { open(Routes.SETTINGS_ALARM) },
                                    onOpenSignalPath = { open(Routes.SIGNAL_PATH) },
                                    onOpenEq = { open(Routes.SETTINGS_EQ) },
                                    onOpenVisualizer = { open(Routes.SETTINGS_VISUALIZER) },
                                    onOpenSonic = { open(Routes.SETTINGS_SONIC) },
                                    onOpenSources = { open(Routes.SETTINGS_SOURCES) },
                                    onOpenMerge = { open(Routes.SETTINGS_MERGE) },
                                    onOpenDownloads = { open(Routes.SETTINGS_STORAGE) },
                                    onOpenAppearance = { open(Routes.SETTINGS_APPEARANCE) },
                                    onOpenLyricsSettings = { open(Routes.SETTINGS_LYRICS) },
                                    onOpenLanguage = { open(Routes.SETTINGS_LANGUAGE) },
                                    onOpenGestures = { open(Routes.SETTINGS_GESTURES) },
                                    onOpenInsights = { open(Routes.SETTINGS_INSIGHTS) },
                                    onOpenIntegrations = { open(Routes.SETTINGS_INTEGRATIONS) },
                                    onOpenPermissions = { open(Routes.SETTINGS_PERMISSIONS) },
                                    onOpenAbout = { open(Routes.SETTINGS_ABOUT) },
                                    onOpenProfile = { navController.navigate(Routes.PROFILE) },
                                    onOpenAccounts = { open(Routes.SETTINGS_ACCOUNTS) },
                                    onOpenBackup = { open(Routes.SETTINGS_BACKUP) },
                                    onLogout = { logout() },
                                    onReplayTour = { onboarding.restart(); navController.navigate(Routes.SETUP) },
                                    onOpenRoute = { open(it) },
                                )
                                }
                            }
                            if (twoPane) {
                                Spacer(Modifier.width(16.dp))
                                Box(
                                    Modifier.weight(1f).fillMaxHeight().padding(top = 8.dp)
                                        .paperClip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
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
                        com.aurora.music.ui.screens.stats.ListeningHistoryScreen(contentPadding = inner, onBack = { navController.popBackStack() }, onPlay = { playById(it) },
                            onShowOnMap = { id, at -> navController.navigate("${Routes.LISTENING_MAP}/${android.net.Uri.encode(id)}/$at") })
                    }
                    composable(Routes.AI_PLAYLIST) {
                        com.aurora.music.ui.screens.aidj.AiPlaylistScreen(
                            contentPadding = inner,
                            current = playerState.current.takeIf { it.id.isNotEmpty() },
                            onBack = { navController.popBackStack() },
                            onPlay = { songs, index -> playerVM.playAll(songs, index) },
                            onMessage = { confirm(it) },
                        )
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
                        com.aurora.music.ui.screens.stats.ListeningStatsScreen(contentPadding = inner, onBack = { navController.popBackStack() }, onPlay = { playById(it) }, onOpenDetail = { k, i -> openDetail(k, i) },
                            onOpenInsights = { w -> navController.navigate("${Routes.INSIGHTS}/${w.period.name}/${w.start}") })
                    }
                    composable(Routes.RADIO) {
                        com.aurora.music.ui.screens.radio.RadioScreen(
                            contentPadding = inner,
                            onBack = { navController.popBackStack() },
                            onPlay = { playerVM.play(it) },
                        )
                    }
                    composable(Routes.EXPLORE) {
                        com.aurora.music.ui.screens.explore.ExploreScreen(
                            contentPadding = inner,
                            onBack = { navController.popBackStack() },
                            onOpenDetail = { kind, id -> openDetail(kind, id) },
                            onPlayAll = { songs, index -> playerVM.playAll(songs, index) },
                            onOpenCategory = { navController.navigate(Routes.exploreCategory(it.params, it.title)) },
                        )
                    }
                    composable(
                        Routes.EXPLORE_CATEGORY,
                        arguments = listOf(
                            androidx.navigation.navArgument("params") { defaultValue = "" },
                            androidx.navigation.navArgument("title") { defaultValue = "" },
                        ),
                    ) { entry ->
                        com.aurora.music.ui.screens.explore.ExploreCategoryScreen(
                            contentPadding = inner,
                            params = entry.arguments?.getString("params").orEmpty(),
                            title = entry.arguments?.getString("title").orEmpty(),
                            onBack = { navController.popBackStack() },
                            onOpenDetail = { kind, id -> openDetail(kind, id) },
                            onPlayAll = { songs, index -> playerVM.playAll(songs, index) },
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
                    onExpand = { if (!playerState.hasTrack) showOutput = true else if (playerState.current.id.startsWith("aurora-mix:")) showMix = true else playerVM.setExpanded(true) },
                    onTogglePlay = { playerVM.togglePlay() },
                    onPrevious = { playerVM.previous() },
                    onNext = { playerVM.next() },
                    onSeek = { playerVM.seekTo(it) },
                    onToggleLike = { playerVM.toggleLikeCurrent() },
                    onOpenOutput = { showOutput = true },
                    onPane = { showPane(it) },
                    modifier = Modifier.align(Alignment.BottomCenter).then(hiddenBehindPlayer)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                        .onSizeChanged { dockHeight = with(density) { it.height.toDp() } },
                )
            }

            MaterialTheme(colorScheme = playerColors) {
            playerTransition.AnimatedVisibility(
                visible = { it },
                enter = slideInVertically(animationSpec = tween(320)) { it } + fadeIn(tween(220)),
                exit = slideOutVertically(animationSpec = tween(280)) { it } + fadeOut(tween(180)),
            ) {
                PlayerScreen(
                    state = playerState,
                    surfaceOffset = playerSurfaceOffset,
                    videoPlayer = playerVM.videoPlayer,
                    onVideoQualityChange = playerVM::setVideoQuality,
                    onRequestVideo = { canvas -> playerVM.requestMusicVideo(canvas) { confirm(it) } },
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
                    djControl = aiDjControl,
                    djStage = aiDjStage,
                    onOpenMix = { playerVM.setExpanded(false); mixQueue = playerState.queue.filterNot { it.id.startsWith("aurora-mix:") }; showMix = true },
                    onPostComment = playerVM::postComment,
                    onToggleComments = playerVM::setCommentsEnabled,
                    onOpenRelated = if (exploreAvailable && !windowLayout.useNavigationRail) ({ showRelated = true }) else null,
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
                    shown = showQueue,
                )
            }
            }
            // the scaffold's snackbar sits under the expanded player, so player messages need their own host above it
            if (playerState.expanded || showQueue) SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 24.dp))

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
    }

    BackHandler(enabled = playerState.expanded && !showMix) { playerVM.setExpanded(false) }

    if (showNavMore) {
        val inBar = navLayout.bar.map { it.id }.toSet()
        NavMoreSheet(
            items = (navLayout.main + navLayout.more).filter { it.id !in inBar }.distinctBy { it.id },
            currentRoute = navRoute,
            onOpen = { item -> showNavMore = false; openNavItem(item) },
            onSettings = { showNavMore = false; navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
            onDismiss = { showNavMore = false },
        )
    }

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
            onOpenReverbSettings = {
                showSpeedSheet = false
                playerVM.setExpanded(false)
                navController.navigate(Routes.SETTINGS_REVERB) { launchSingleTop = true }
            },
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
    if (showRelated && playerState.hasTrack) {
        com.aurora.music.ui.screens.player.RelatedSheet(
            song = playerState.current,
            onDismiss = { showRelated = false },
            onOpenDetail = { kind, id -> showRelated = false; playerVM.setExpanded(false); openDetail(kind, id) },
            onPlayAll = { songs, index -> playerVM.playAll(songs, index) },
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
    matchSong?.let { com.aurora.music.ui.components.MatchPickerSheet(it) { matchSong = null } }

    val tour = whatsNew?.let { versions -> WhatsNewCatalog.releases.filter { it.version in versions }.reversed() }
    if (tour != null) {
        val whatsNewEnabled by container.settingsStore.whatsNewEnabled.collectAsStateWithLifecycle(initialValue = true)
        fun closeWhatsNew() {
            whatsNew = null
            whatsNewTrying = false
            scope.launch { container.settingsStore.setWhatsNewSeen(com.aurora.music.BuildConfig.VERSION_NAME) }
        }
        when {
            tour.isEmpty() -> androidx.compose.runtime.LaunchedEffect(Unit) { closeWhatsNew() }
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
                    onEnabledChange = { enabled -> scope.launch { container.settingsStore.setWhatsNewEnabled(enabled) } },
                    onTry = { route ->
                        whatsNewTrying = true
                        playerVM.setExpanded(false)
                        navController.navigate(route) { launchSingleTop = true }
                    },
                    onClose = ::closeWhatsNew,
                )
            }
        }
    }
}

@Composable
private fun DownloadProgressBanner(count: Int) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val states by container.downloadManager.states.collectAsStateWithLifecycle()
    // active means still queued or in flight
    val progress = states.values.mapNotNull { (it as? com.aurora.music.data.DownloadState.Downloading)?.progress }
        .let { if (it.isEmpty()) 0f else it.sum() / count.coerceAtLeast(1) }
    val classic = com.aurora.music.ui.theme.LocalUiPrefs.current.themeStyle == com.aurora.music.data.ThemeStyle.AURORA
    val animated by androidx.compose.animation.core.animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(300),
        label = "dlProgress",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (classic) Modifier.paperClip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f))
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(18.dp))
                else if (com.aurora.music.ui.theme.isGlassTheme()) Modifier.liquidGlass(com.aurora.music.ui.theme.LocalGlassBackdrop.current, com.kyant.shapes.RoundedRectangle(24.dp), refraction = 16.dp)
                else Modifier.auroraPanel(MaterialTheme.shapes.large))
            .padding(horizontal = 16.dp, vertical = 11.dp),
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
                modifier = Modifier.fillMaxWidth().paperClip(RoundedCornerShape(50)),
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
private fun FloatingNav(
    currentRoute: String?,
    tabs: List<com.aurora.music.navigation.NavMenuItem>,
    moreTab: Boolean,
    onNavigate: (String) -> Unit,
    onMore: () -> Unit,
    onLongPress: (String) -> Unit = {},
) {
    if (com.aurora.music.ui.theme.isGlassTheme()) {
        GlassFloatingNav(currentRoute, tabs, moreTab, onNavigate, onMore, onLongPress)
        return
    }
    val classic = com.aurora.music.ui.theme.LocalUiPrefs.current.themeStyle == com.aurora.music.data.ThemeStyle.AURORA
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (classic) Modifier.paperClip(RoundedCornerShape(26.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f))
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(26.dp))
                else Modifier.auroraPanel(MaterialTheme.shapes.extraLarge, emphasized = true))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            val selected = currentRoute == tab.route
            NavTab(tab.shortLabel, if (selected) tab.selectedIcon else tab.icon, selected, classic,
                Modifier.weight(if (selected) 1.4f else 1f).longPressFirst(tab.route) { _, _ -> onLongPress(tab.route) }) {
                onNavigate(tab.route)
            }
        }
        if (moreTab) NavTab(appString(R.string.text_more_4bab2d), Icons.Filled.MoreHoriz, false, classic, Modifier.weight(1f), onMore)
    }
}

@Composable
private fun NavTab(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, classic: Boolean,
    modifier: Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, label = "navBg")
    val content = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .clip(if (classic) RoundedCornerShape(50) else MaterialTheme.shapes.small)
            .background(bg)
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, label, tint = content, modifier = Modifier.size(22.dp))
        AnimatedVisibility(visible = selected) {
            Row {
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = content,
                    maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
}

// ios 26 layout: tabs in one glass capsule, search as its own glass circle
@Composable
private fun GlassFloatingNav(
    currentRoute: String?,
    tabs: List<com.aurora.music.navigation.NavMenuItem>,
    moreTab: Boolean,
    onNavigate: (String) -> Unit,
    onMore: () -> Unit,
    onLongPress: (String) -> Unit,
) {
    val backdrop = com.aurora.music.ui.theme.LocalGlassBackdrop.current
    val search = tabs.firstOrNull { it.route == Routes.SEARCH }
    val capsule = tabs.filter { it != search }
    val moreLabel = appString(R.string.text_more_4bab2d)
    val glassTabs = capsule.map { com.aurora.music.ui.components.glass.GlassTab(it.shortLabel, it.selectedIcon) } +
        if (moreTab) listOf(com.aurora.music.ui.components.glass.GlassTab(moreLabel, Icons.Filled.MoreHoriz)) else emptyList()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        com.aurora.music.ui.components.glass.GlassTabBar(
            tabs = glassTabs,
            selectedIndex = capsule.indexOfFirst { it.route == currentRoute },
            onSelect = { index -> capsule.getOrNull(index)?.let { onNavigate(it.route) } ?: onMore() },
            backdrop = backdrop,
            modifier = Modifier.weight(1f),
            onLongPress = { index -> capsule.getOrNull(index)?.let { onLongPress(it.route) } },
        )
        if (search != null) {
            val selected = currentRoute == search.route
            Spacer(Modifier.width(12.dp))
            com.aurora.music.ui.components.glass.GlassButton(
                onClick = { onNavigate(search.route) },
                backdrop = backdrop,
                modifier = Modifier.size(64.dp),
                selected = selected,
                contentDescription = search.shortLabel,
                onLongClick = { onLongPress(search.route) },
            ) {
                Icon(search.selectedIcon, null, Modifier.size(28.dp),
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

// everything the tab bar leaves out so a phone never depends on finding the side menu
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun NavMoreSheet(
    items: List<com.aurora.music.navigation.NavMenuItem>,
    currentRoute: String?,
    onOpen: (com.aurora.music.navigation.NavMenuItem) -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(appString(R.string.text_more_4bab2d), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp))
            items.forEach { item -> NavSheetRow(item.icon, item.label, currentRoute == item.route) { onOpen(item) } }
            NavSheetRow(Icons.Filled.Settings, appString(R.string.text_settings_c7f73b), currentRoute == Routes.SETTINGS, onSettings)
        }
    }
}

@Composable
private fun NavSheetRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val content = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = content, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = content)
    }
}
