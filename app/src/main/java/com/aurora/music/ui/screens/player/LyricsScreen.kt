package com.aurora.music.ui.screens.player

import android.graphics.drawable.BitmapDrawable
import com.aurora.music.ui.theme.paperClip
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import com.aurora.music.data.Lyrics
import com.aurora.music.data.LyricsSync
import com.aurora.music.data.LyricsTranslation
import com.aurora.music.data.VocalActivity
import com.aurora.music.data.charFill
import com.aurora.music.data.withWordTiming
import com.aurora.music.localization.appString
import com.aurora.music.model.Song
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.formatTime
import com.aurora.music.ui.theme.useDarkTheme
import com.aurora.music.viewmodel.PlayerUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import com.aurora.music.model.accent

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun LyricsScreen(
    state: PlayerUiState,
    onClose: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
) {
    val song = state.current
    val view = LocalView.current
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionVersion by remember { mutableIntStateOf(0) }
    var pointerHeld by remember { mutableStateOf(false) }
    var scrolling by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf(false) }
    val accessibility = remember(view) {
        view.context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
    }
    var touchExploration by remember { mutableStateOf(accessibility.isTouchExplorationEnabled) }
    DisposableEffect(accessibility) {
        val listener = android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener { touchExploration = it }
        accessibility.addTouchExplorationStateChangeListener(listener)
        onDispose { accessibility.removeTouchExplorationStateChangeListener(listener) }
    }
    val controlsAlpha by animateFloatAsState(if (controlsVisible) 1f else 0f, tween(350), label = "lyricsControls")
    val bars = remember(view) {
        (view.context as? android.app.Activity)?.window?.let { WindowCompat.getInsetsController(it, view) }
    }
    // the mode can flip while open since uimode doesn't recreate the activity
    val appDark by rememberUpdatedState(com.aurora.music.ui.theme.LocalUiPrefs.current.useDarkTheme())
    DisposableEffect(view) {
        val originalInsets = ViewCompat.getRootWindowInsets(view)
        val statusVisible = originalInsets?.isVisible(WindowInsetsCompat.Type.statusBars()) != false
        val navigationVisible = originalInsets?.isVisible(WindowInsetsCompat.Type.navigationBars()) != false
        val originalBehavior = bars?.systemBarsBehavior
        onDispose {
            bars?.isAppearanceLightStatusBars = !appDark
            bars?.isAppearanceLightNavigationBars = !appDark
            if (originalBehavior != null) bars.systemBarsBehavior = originalBehavior
            if (statusVisible) bars?.show(WindowInsetsCompat.Type.statusBars()) else bars?.hide(WindowInsetsCompat.Type.statusBars())
            if (navigationVisible) bars?.show(WindowInsetsCompat.Type.navigationBars()) else bars?.hide(WindowInsetsCompat.Type.navigationBars())
        }
    }
    val wide = com.aurora.music.ui.layout.LocalWindowLayout.current.useLandscapePlayer
    LaunchedEffect(controlsVisible, wide) {
        bars?.isAppearanceLightStatusBars = false
        bars?.isAppearanceLightNavigationBars = false
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (controlsVisible && !wide) bars?.show(WindowInsetsCompat.Type.systemBars())
        else bars?.hide(WindowInsetsCompat.Type.systemBars())
    }
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    var lyrics by remember(song.id, song.playbackSource?.providerId) { mutableStateOf<Lyrics?>(null) }
    var loading by remember(song.id, song.playbackSource?.providerId) { mutableStateOf(true) }
    LaunchedEffect(song.id, song.playbackSource?.providerId) {
        controlsVisible = true
        interactionVersion++
        lyrics = if (song.id.isBlank()) null else container.lyricsRepository.lyricsFor(song)
        loading = false
    }
    if (wide) {
        WideLyrics(state, lyrics, loading, onClose, onTogglePlay, onPrevious, onNext, onSeek)
        return
    }
    LaunchedEffect(interactionVersion, pointerHeld, scrolling, seeking, loading, lyrics, touchExploration) {
        if (touchExploration) controlsVisible = true
        if (!pointerHeld && !scrolling && !seeking && !loading && !lyrics?.lines.isNullOrEmpty() && !touchExploration) {
            delay(3000)
            controlsVisible = false
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF17191D)).pointerInput(Unit) {
        // Keep the idle timer suspended during gestures without changing control visibility.
        awaitPointerEventScope {
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressed = event.changes.any { it.pressed }
                    if (!pointerHeld && pressed) {
                        interactionVersion++
                    }
                    if (pointerHeld && !pressed) interactionVersion++
                    pointerHeld = pressed
                }
            } finally {
                pointerHeld = false
            }
        }
    }.clickable(
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
        indication = null,
    ) { controlsVisible = !controlsVisible }) {
        LyricsBackdrop(song)
        Column(Modifier.align(Alignment.TopCenter).widthIn(max = 880.dp).fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility)) {
            Row(Modifier.fillMaxWidth().lyricsChrome(controlsVisible, controlsAlpha)
                .padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Artwork(song.artworkUrl, song.accent, Modifier.size(44.dp), corner = 10.dp)
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(song.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, color = Color.White.copy(alpha = .65f), fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val translating = com.aurora.music.ui.theme.LocalUiPrefs.current.lyricsTranslate
                val scope = rememberCoroutineScope()
                IconButton(onClick = { interactionVersion++; scope.launch { container.settingsStore.setLyricsTranslate(!translating) } },
                    enabled = controlsVisible) {
                    Icon(Icons.Default.Translate, appString(R.string.lyrics_translate),
                        tint = if (translating) Color.White else Color.White.copy(alpha = .45f))
                }
                IconButton(onClick = onClose, enabled = controlsVisible) {
                    Icon(Icons.Default.KeyboardArrowDown, appString(R.string.text_collapse_9cf188), tint = Color.White)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val content = lyrics
                when {
                    loading -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp),
                        color = Color.White.copy(alpha = .8f), strokeWidth = 2.dp)
                    content == null || content.lines.isEmpty() -> Column(
                        Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Default.Lyrics, null, Modifier.size(40.dp), tint = Color.White.copy(alpha = .55f))
                        Spacer(Modifier.height(16.dp))
                        Text(appString(R.string.text_no_lyrics_found_75127a), color = Color.White,
                            fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                    else -> key(song.id, song.playbackSource?.providerId) {
                        ImmersiveLyrics(content, state.current, state.isPlaying, state.durationSec,
                            onSeek = { interactionVersion++; onSeek(it) }, onScrolling = { scrolling = it },
                            onBackgroundTap = { controlsVisible = !controlsVisible })
                    }
                }
            }
            Box(Modifier.lyricsChrome(controlsVisible, controlsAlpha)) {
                LyricsTransport(state, lyrics?.source,
                    onTogglePlay = { interactionVersion++; onTogglePlay() },
                    onPrevious = { interactionVersion++; onPrevious() },
                    onNext = { interactionVersion++; onNext() },
                    onSeek = { interactionVersion++; onSeek(it) },
                    enabled = controlsVisible, onSeeking = { seeking = it })
            }
        }
    }
}

@Composable
private fun WideLyrics(
    state: PlayerUiState,
    lyrics: Lyrics?,
    loading: Boolean,
    onClose: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
) {
    val song = state.current
    val ui = com.aurora.music.ui.theme.LocalUiPrefs.current
    Box(Modifier.fillMaxSize().background(Color(0xFF17191D)).clickable(
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null,
    ) {}) {
        LyricsBackdrop(song)
        Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout).padding(horizontal = 56.dp, vertical = 32.dp)) {
            BoxWithConstraints(Modifier.weight(0.42f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                val art = minOf(maxWidth, maxHeight * .5f, 420.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 460.dp)) {
                    Artwork(song.artworkUrl, song.accent, Modifier.size(art), corner = 16.dp)
                    Spacer(Modifier.height(24.dp))
                    Text(song.title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, color = Color.White.copy(alpha = .65f), style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(20.dp))
                    if (state.durationSec > 0) {
                        val positionSec = com.aurora.music.ui.components.LocalPlaybackPosition.current.value
                        com.aurora.music.ui.components.Waveform(
                            progress = state.progressAt(positionSec), accent = Color.White, onSeek = onSeek,
                            modifier = Modifier.fillMaxWidth(), seed = song.id.hashCode(),
                            barCount = ui.playerWaveBars.coerceIn(24, 96), height = 40.dp,
                        )
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(formatTime(positionSec.toInt()), color = Color.White.copy(alpha = .65f), style = MaterialTheme.typography.labelSmall)
                            Text(formatTime(state.durationSec), color = Color.White.copy(alpha = .65f), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onPrevious, Modifier.size(52.dp)) {
                            Icon(Icons.Default.SkipPrevious, appString(R.string.text_previous_50f942), Modifier.size(28.dp), tint = Color.White)
                        }
                        Spacer(Modifier.width(20.dp))
                        IconButton(onTogglePlay, Modifier.size(60.dp).background(Color.White.copy(alpha = .16f), CircleShape)) {
                            Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                appString(R.string.text_play_pause_14a1d0), Modifier.size(32.dp), tint = Color.White)
                        }
                        Spacer(Modifier.width(20.dp))
                        IconButton(onNext, Modifier.size(52.dp)) {
                            Icon(Icons.Default.SkipNext, appString(R.string.text_next_bc9819), Modifier.size(28.dp), tint = Color.White)
                        }
                    }
                }
            }
            Spacer(Modifier.width(48.dp))
            Box(Modifier.weight(0.58f).fillMaxHeight()) {
                when {
                    loading -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp),
                        color = Color.White.copy(alpha = .8f), strokeWidth = 2.dp)
                    lyrics == null || lyrics.lines.isEmpty() -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Lyrics, null, Modifier.size(40.dp), tint = Color.White.copy(alpha = .55f))
                        Spacer(Modifier.height(16.dp))
                        Text(appString(R.string.text_no_lyrics_found_75127a), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                    else -> key(song.id, song.playbackSource?.providerId) {
                        ImmersiveLyrics(lyrics, state.current, state.isPlaying, state.durationSec, onSeek = onSeek, onScrolling = {}, onBackgroundTap = {})
                    }
                }
            }
        }
        IconButton(onClose, Modifier.align(Alignment.TopStart).padding(12.dp)) {
            Icon(Icons.Default.CloseFullscreen, appString(R.string.text_collapse_9cf188), tint = Color.White.copy(alpha = .8f))
        }
        if (!lyrics?.source.isNullOrBlank()) Text(lyrics?.source.orEmpty(), color = Color.White.copy(alpha = .4f),
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }
}

@Composable
internal fun LyricsPane(state: PlayerUiState, onSeek: (Float) -> Unit, modifier: Modifier = Modifier, compact: Boolean = true) {
    val song = state.current
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    var lyrics by remember(song.id, song.playbackSource?.providerId) { mutableStateOf<Lyrics?>(null) }
    var loading by remember(song.id, song.playbackSource?.providerId) { mutableStateOf(true) }
    LaunchedEffect(song.id, song.playbackSource?.providerId) {
        lyrics = if (song.id.isBlank()) null else container.lyricsRepository.lyricsFor(song)
        loading = false
    }
    Box(modifier) {
        LyricsBackdrop(song)
        val content = lyrics
        when {
            loading -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp),
                color = Color.White.copy(alpha = .8f), strokeWidth = 2.dp)
            content == null || content.lines.isEmpty() -> Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Default.Lyrics, null, Modifier.size(36.dp), tint = Color.White.copy(alpha = .55f))
                Spacer(Modifier.height(12.dp))
                Text(appString(R.string.text_no_lyrics_found_75127a), color = Color.White,
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (song.title.isNotBlank()) Text(song.title, color = Color.White.copy(alpha = .65f),
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp))
            }
            else -> Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    key(song.id, song.playbackSource?.providerId) {
                        ImmersiveLyrics(content, state.current, state.isPlaying, state.durationSec, onSeek = onSeek,
                            onScrolling = {}, onBackgroundTap = {}, compact = compact)
                    }
                }
                if (!content.source.isNullOrBlank()) Text(content.source, color = Color.White.copy(alpha = .45f),
                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 10.dp))
            }
        }
    }
}

private fun Modifier.lyricsChrome(visible: Boolean, alpha: Float) =
    graphicsLayer { this.alpha = alpha }.then(if (visible) Modifier else Modifier.clearAndSetSemantics {})

// Extract several real artwork tones; app accents and Material You never enter this palette.
@Composable
internal fun LyricsBackdrop(song: Song) {
    val context = LocalContext.current
    val neutral = listOf(Color(0xFF39434B), Color(0xFF242B34), Color(0xFF50565A))
    var tones by remember(song.artworkUrl) { mutableStateOf(neutral) }
    LaunchedEffect(song.artworkUrl) {
        if (song.artworkUrl.isBlank()) return@LaunchedEffect
        val extracted = withContext(Dispatchers.IO) {
            val result = context.imageLoader.execute(ImageRequest.Builder(context)
                .data(song.artworkUrl).allowHardware(false).size(160).build())
            val bitmap = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
                ?: return@withContext null
            val palette = Palette.from(bitmap).clearFilters().generate()
            val dominant = palette.dominantSwatch ?: return@withContext null
            val primary = palette.vibrantSwatch ?: dominant
            val secondary = palette.swatches.filter { it.hsl[1] > .2f && it.hsl[2] in .12f.. .8f }
                .maxByOrNull {
                    val hueDistance = abs(it.hsl[0] - primary.hsl[0]).let { distance -> minOf(distance, 360f - distance) }
                    hueDistance * kotlin.math.sqrt(it.population.toFloat())
                } ?: palette.mutedSwatch ?: dominant
            listOf(primary, secondary,
                palette.lightVibrantSwatch ?: palette.lightMutedSwatch ?: dominant).map {
                var tone = Color(it.rgb)
                while (tone.luminance() > .2f) tone = lerp(tone, Color.Black, .1f)
                tone
            }
        }
        if (extracted != null) tones = extracted
    }
    val first by animateColorAsState(tones[0], tween(1100), label = "lyricsPrimary")
    val second by animateColorAsState(tones[1], tween(1100), label = "lyricsSecondary")
    val third by animateColorAsState(tones[2], tween(1100), label = "lyricsGlow")
    // the glow drifts under a pixel per frame so a dozen redraws a second look identical and cost far less
    val driftState = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (true) {
            val phase = ((withFrameMillis { it } - start) % 36_000L) / 18_000f
            driftState.floatValue = if (phase <= 1f) phase else 2f - phase
            delay(80)
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val drift = driftState.floatValue
        drawRect(lerp(second, Color.Black, .58f))
        val radius = size.maxDimension * .85f
        drawCircle(Brush.radialGradient(listOf(first.copy(alpha = .72f), first.copy(alpha = 0f)),
            Offset(size.width * (.1f + drift * .55f), size.height * .25f), radius),
            radius, Offset(size.width * (.1f + drift * .55f), size.height * .25f))
        drawCircle(Brush.radialGradient(listOf(third.copy(alpha = .45f), third.copy(alpha = 0f)),
            Offset(size.width * (.95f - drift * .35f), size.height * .8f), radius * .75f),
            radius * .75f, Offset(size.width * (.95f - drift * .35f), size.height * .8f))
        drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .3f),
            Color.Black.copy(alpha = .18f), Color.Black.copy(alpha = .52f))))
    }
}

@Composable
private fun ImmersiveLyrics(source: Lyrics, song: Song, playing: Boolean, durationSec: Int, onSeek: (Float) -> Unit,
    onScrolling: (Boolean) -> Unit, onBackgroundTap: () -> Unit, compact: Boolean = false) {
    val mode = com.aurora.music.ui.theme.LocalUiPrefs.current.lyricsSync
    val vocals = rememberVocals(song, source, mode)
    // off the main thread so a cached fetch doesn't put the timing pass in the slide-in
    val estimate by produceState(source, source, mode) {
        value = if (mode == LyricsSync.LINE) source
            else withContext(Dispatchers.Default) { runCatching { source.withWordTiming() }.getOrDefault(source) }
    }
    // the vocal fit runs off the main thread; the estimate shows meanwhile
    val heard by produceState<Triple<Lyrics, VocalActivity, Lyrics>?>(null, source, vocals, mode) {
        value = null
        // a failed fit falls back to the paced estimate instead of taking the app down
        if (mode != LyricsSync.LINE && vocals != null) value = withContext(Dispatchers.Default) {
            runCatching { source.withWordTiming(vocals) }.getOrNull()
        }?.let { Triple(source, vocals, it) }
    }
    val lyrics = heard?.takeIf { it.first === source && it.second === vocals }?.third ?: estimate
    val lines = lyrics.lines
    val translation = rememberTranslation(source)
    val translated = (translation as? LyricsTranslation.Ready)?.lines?.takeIf { it.size == lines.size }
    val clock = rememberLyricsClock(com.aurora.music.ui.components.LocalPlaybackPosition.current, playing && mode != LyricsSync.LINE)
    val current by remember(lyrics) {
        derivedStateOf { if (lyrics.synced) lines.indexOfLast { it.startMs >= 0 && it.startMs <= clock.longValue } else -1 }
    }
    val list = rememberLazyListState()
    var browsing by remember { mutableStateOf(false) }
    val reportScrolling by rememberUpdatedState(onScrolling)
    LaunchedEffect(list) {
        snapshotFlow { browsing && list.isScrollInProgress }.collect { reportScrolling(it) }
    }
    DisposableEffect(Unit) { onDispose { reportScrolling(false) } }
    LaunchedEffect(list) {
        var settle: Job? = null
        list.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> { settle?.cancel(); browsing = true }
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    settle?.cancel()
                    settle = launch { delay(4500); browsing = false }
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val focus = maxHeight * .26f
        LaunchedEffect(current, browsing, maxHeight) {
            if (!lyrics.synced || browsing) return@LaunchedEffect
            val target = current.coerceAtLeast(0)
            val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
            // Item offsets exclude the before-content padding, where the focus line sits.
            if (item != null) list.animateScrollBy(item.offset.toFloat(), tween(650, easing = FastOutSlowInEasing))
            else list.animateScrollToItem(target)
        }
        LazyColumn(state = list,
            modifier = Modifier.fillMaxSize().lyricsEdgeFade().padding(horizontal = if (compact) 24.dp else 28.dp),
            contentPadding = PaddingValues(top = if (lyrics.synced) focus else 32.dp,
                bottom = if (lyrics.synced) maxHeight * .7f else 48.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 22.dp),
        ) {
            items(lines.size) { index ->
                var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
                val seekable = lyrics.synced && durationSec > 0 && lines[index].startMs >= 0
                val seekLine by rememberUpdatedState {
                    if (seekable) {
                        browsing = false
                        onSeek((lines[index].startMs / 1000f / durationSec).coerceIn(0f, 1f))
                    }
                }
                val backgroundTap by rememberUpdatedState(onBackgroundTap)
                val active = index == current
                val alpha by animateFloatAsState(when {
                    !lyrics.synced || browsing -> .88f
                    active -> 1f
                    index < current -> .35f
                    else -> .55f
                }, tween(420), label = "lineOpacity")
                val scale by animateFloatAsState(if (active || !lyrics.synced || browsing) 1f else .96f,
                    spring(dampingRatio = .85f, stiffness = 160f), label = "lineEmphasis")
                val soften by animateFloatAsState(
                    if (!lyrics.synced || browsing || active) 0f
                    else ((abs(index - current) - 1).coerceAtLeast(0) * 1.6f).coerceAtMost(7f),
                    tween(450), label = "lineSoftness")
                val line = lines[index]
                val karaokeOn = mode != LyricsSync.LINE && active && !browsing && line.words.isNotEmpty()
                // the wipe advances in quarter-character steps so the line re-lays out only when its look changes
                val fill by remember(line, karaokeOn) {
                    derivedStateOf(structuralEqualityPolicy()) {
                        if (karaokeOn) line.charFill(clock.longValue).map { (it * FILL_STEPS).roundToInt() / FILL_STEPS } else null
                    }
                }
                val text = fill?.let { remember(line, it) { karaoke(line.displayText, it) } }
                    ?: AnnotatedString(line.displayText.ifBlank { "•••" })
                val meaning = translated?.getOrNull(index)
                // fades live in the layer so a line change doesn't recompose every visible line per frame
                Column(Modifier.fillMaxWidth().graphicsLayer {
                    scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, .5f)
                    this.alpha = alpha
                    val r = soften.dp.toPx()
                    renderEffect = if (soften > .5f) BlurEffect(r, r, TileMode.Clamp) else null
                    clip = renderEffect != null
                    // modulate keeps unblurred dim lines off an offscreen buffer
                    compositingStrategy = if (renderEffect == null) CompositingStrategy.ModulateAlpha else CompositingStrategy.Auto
                }.semantics(mergeDescendants = true) {
                    selected = active
                    if (seekable) onClick { seekLine(); true }
                }) {
                    Text(text,
                        onTextLayout = { textLayout = it },
                        color = Color.White, fontSize = if (compact) 22.sp else 30.sp,
                        lineHeight = if (compact) 29.sp else 38.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = (-.5).sp,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).pointerInput(seekable) {
                            detectTapGestures { point ->
                                val layout = textLayout
                                val line = layout?.getLineForVerticalPosition(point.y)
                                val onText = layout != null && line != null &&
                                    point.y >= layout.getLineTop(line) && point.y < layout.getLineBottom(line) &&
                                    point.x >= layout.getLineLeft(line) && point.x < layout.getLineRight(line)
                                if (onText && seekable) seekLine() else backgroundTap()
                            }
                        },
                    )
                    if (meaning != null) Text(meaning,
                        color = Color.White.copy(alpha = .6f), fontSize = if (compact) 14.sp else 17.sp,
                        lineHeight = if (compact) 19.sp else 23.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).pointerInput(seekable) {
                            detectTapGestures { if (seekable) seekLine() else backgroundTap() }
                        },
                    )
                }
            }
        }
        val pending = translation as? LyricsTranslation.Pending
        if (pending != null) {
            val language = java.util.Locale.forLanguageTag(pending.language)
                .getDisplayLanguage(com.aurora.music.localization.AppStrings.context.resources.configuration.locales[0])
            Text(appString(if (pending.needsWifi) R.string.lyrics_translate_wifi else R.string.lyrics_translate_downloading, language),
                color = Color.White.copy(alpha = .8f), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp).paperClip(CircleShape)
                    .background(Color.Black.copy(alpha = .4f)).padding(horizontal = 14.dp, vertical = 7.dp))
        }
        androidx.compose.animation.AnimatedVisibility(browsing && lyrics.synced,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) {
            Text(appString(R.string.text_back_to_current_line_d5146a), color = Color.White,
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.paperClip(CircleShape).background(Color.Black.copy(alpha = .5f))
                    .clickable(role = Role.Button) { browsing = false }
                    .padding(horizontal = 20.dp, vertical = 14.dp))
        }
    }
}

@Composable
private fun rememberTranslation(lyrics: Lyrics): LyricsTranslation? {
    val enabled = com.aurora.music.ui.theme.LocalUiPrefs.current.lyricsTranslate
    val translator = (LocalContext.current.applicationContext as AuroraApplication).container.lyricsTranslator
    val target = com.aurora.music.localization.AppStrings.context.resources.configuration.locales[0].language
    val translation by produceState<LyricsTranslation?>(null, lyrics, enabled, target) {
        value = null
        if (enabled) translator.translate(lyrics.lines.map { it.displayText }, target).collect { value = it }
    }
    return translation
}

// only songs without real word timing are analyzed; the estimate shows until the result arrives
@Composable
private fun rememberVocals(song: Song, lyrics: Lyrics, mode: Int): VocalActivity? {
    val analysis = (LocalContext.current.applicationContext as AuroraApplication).container.vocalAnalysis
    val wanted = mode == LyricsSync.VOCALS && lyrics.synced && lyrics.lines.none { it.words.isNotEmpty() }
    val vocals by produceState<VocalActivity?>(null, song.id, song.playbackSource?.providerId, wanted) {
        value = if (wanted) analysis.activityFor(song) else null
    }
    return vocals
}

// extrapolates between the 200ms player ticks at about 30 Hz, plenty for the soft word wipe
@Composable
private fun rememberLyricsClock(position: State<Float>, playing: Boolean): MutableLongState {
    val clock = remember { mutableLongStateOf((androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { position.value } * 1000).toLong()) }
    LaunchedEffect(position, playing) {
        snapshotFlow { position.value }.collectLatest { positionSec ->
            val reported = (positionSec * 1000).toLong()
            val ahead = clock.longValue - reported
            val base = if (playing && ahead in 1L..250L) reported + ahead / 2 else reported
            clock.longValue = base
            if (!playing) return@collectLatest
            val start = withFrameMillis { it }
            while (true) {
                withFrameMillis { clock.longValue = base + (it - start) }
                delay(25)
            }
        }
    }
    return clock
}

private const val FILL_STEPS = 4f

private fun karaoke(text: String, fill: List<Float>): AnnotatedString = buildAnnotatedString {
    append(text)
    var i = 0
    while (i < text.length) {
        var j = i + 1
        while (j < text.length && fill[j] == fill[i]) j++
        addStyle(SpanStyle(color = Color.White.copy(alpha = .4f + .6f * fill[i])), i, j)
        i = j
    }
}

private fun Modifier.lyricsEdgeFade() = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(Brush.verticalGradient(0f to Color.Transparent, .07f to Color.Black,
            .9f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
    }

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun LyricsTransport(state: PlayerUiState, source: String?, onTogglePlay: () -> Unit,
    onPrevious: () -> Unit, onNext: () -> Unit, onSeek: (Float) -> Unit,
    enabled: Boolean, onSeeking: (Boolean) -> Unit) {
    var seeking by remember(state.current.id) { mutableStateOf<Float?>(null) }
    val reportSeeking by rememberUpdatedState(onSeeking)
    LaunchedEffect(seeking != null) { reportSeeking(seeking != null) }
    DisposableEffect(Unit) { onDispose { reportSeeking(false) } }
    val positionSec = com.aurora.music.ui.components.LocalPlaybackPosition.current.value
    val progress = seeking ?: if (state.durationSec > 0) positionSec / state.durationSec else 0f
    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp).padding(bottom = 12.dp)) {
        if (state.durationSec > 0) {
            Slider(value = progress.coerceIn(0f, 1f), onValueChange = { seeking = it },
                enabled = enabled,
                onValueChangeFinished = { seeking?.let(onSeek); seeking = null },
                thumb = { Box(Modifier.size(width = 3.dp, height = 16.dp).background(Color.White, CircleShape)) },
                track = { slider ->
                    Box(Modifier.fillMaxWidth().height(3.dp).paperClip(CircleShape).background(Color.White.copy(alpha = .2f))) {
                        Box(Modifier.fillMaxWidth(slider.value.coerceIn(0f, 1f)).fillMaxHeight().background(Color.White))
                    }
                },
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = .18f)), modifier = Modifier.height(32.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime((progress * state.durationSec).toInt()), color = Color.White.copy(alpha = .65f), fontSize = 11.sp)
                Text(formatTime(state.durationSec), color = Color.White.copy(alpha = .65f), fontSize = 11.sp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onPrevious, Modifier.size(56.dp), enabled = enabled) {
                Icon(Icons.Default.SkipPrevious, appString(R.string.text_previous_50f942), Modifier.size(30.dp), tint = Color.White)
            }
            Spacer(Modifier.width(24.dp))
            IconButton(onTogglePlay, Modifier.size(60.dp).background(Color.White.copy(alpha = .14f), CircleShape), enabled = enabled) {
                Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    appString(R.string.text_play_pause_14a1d0), Modifier.size(34.dp), tint = Color.White)
            }
            Spacer(Modifier.width(24.dp))
            IconButton(onNext, Modifier.size(56.dp), enabled = enabled) {
                Icon(Icons.Default.SkipNext, appString(R.string.text_next_bc9819), Modifier.size(30.dp), tint = Color.White)
            }
        }
        if (!source.isNullOrBlank()) Text(source, color = Color.White.copy(alpha = .45f), fontSize = 10.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
    }
}
