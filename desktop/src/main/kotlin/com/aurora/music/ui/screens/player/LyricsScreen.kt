package com.aurora.music.ui.screens.player

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.LocalPlatformContext
import com.aurora.music.R
import com.aurora.music.data.Lyrics
import com.aurora.music.desktop.player.PlayerUiState
import com.aurora.music.desktop.ui.LocalDesktopContainer
import com.aurora.music.localization.appString
import com.aurora.music.model.Song
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.formatTime
import com.aurora.music.util.artworkPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import com.aurora.music.model.accent

@Composable
internal fun LyricsScreen(
    state: PlayerUiState,
    onClose: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    loadLyrics: suspend (Song) -> Lyrics? = LocalDesktopContainer.current.lyricsRepository::lyricsFor,
) {
    val song = state.current
    var lyrics by remember(song.id, song.playbackSource?.providerId) { mutableStateOf<Lyrics?>(null) }
    var loading by remember(song.id, song.playbackSource?.providerId) { mutableStateOf(true) }
    LaunchedEffect(song.id, song.playbackSource?.providerId) {
        lyrics = if (song.id.isBlank()) null else loadLyrics(song)
        loading = false
    }
    WideLyrics(state, lyrics, loading, onClose, onTogglePlay, onPrevious, onNext, onSeek)
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
        Row(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 32.dp)) {
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
                        com.aurora.music.ui.components.Waveform(
                            progress = state.progress, accent = Color.White, onSeek = onSeek,
                            modifier = Modifier.fillMaxWidth(), seed = song.id.hashCode(),
                            barCount = ui.playerWaveBars.coerceIn(24, 96), height = 40.dp,
                        )
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(formatTime(state.positionSec.toInt()), color = Color.White.copy(alpha = .65f), style = MaterialTheme.typography.labelSmall)
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
                        ImmersiveLyrics(lyrics, state.positionSec, state.durationSec, onSeek = onSeek)
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

internal val LocalLyricsAvailability = staticCompositionLocalOf<((Boolean) -> Unit)?> { null }

@Composable
internal fun LyricsPane(
    state: PlayerUiState,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = true,
    loadLyrics: suspend (Song) -> Lyrics? = LocalDesktopContainer.current.lyricsRepository::lyricsFor,
) {
    val song = state.current
    val report by rememberUpdatedState(LocalLyricsAvailability.current)
    var lyrics by remember(song.id, song.playbackSource?.providerId) { mutableStateOf<Lyrics?>(null) }
    var loading by remember(song.id, song.playbackSource?.providerId) { mutableStateOf(true) }
    LaunchedEffect(song.id, song.playbackSource?.providerId) {
        lyrics = if (song.id.isBlank()) null else loadLyrics(song)
        loading = false
        report?.invoke(lyrics?.lines.isNullOrEmpty().not())
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
                        ImmersiveLyrics(content, state.positionSec, state.durationSec, onSeek = onSeek, compact = compact)
                    }
                }
                if (content.source.isNotBlank()) Text(content.source, color = Color.White.copy(alpha = .45f),
                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 10.dp))
            }
        }
    }
}

// artwork tones only app accents never enter this palette
@Composable
private fun LyricsBackdrop(song: Song) {
    val context = LocalPlatformContext.current
    val neutral = listOf(Color(0xFF39434B), Color(0xFF242B34), Color(0xFF50565A))
    var tones by remember(song.artworkUrl) { mutableStateOf(neutral) }
    LaunchedEffect(song.artworkUrl) {
        if (song.artworkUrl.isBlank()) return@LaunchedEffect
        val extracted = withContext(Dispatchers.IO) {
            val palette = artworkPalette(context, song.artworkUrl) ?: return@withContext null
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
    val motion = rememberInfiniteTransition(label = "lyricsAtmosphere")
    val drift by motion.animateFloat(0f, 1f,
        infiniteRepeatable(tween(18000, easing = LinearEasing), RepeatMode.Reverse), label = "drift")
    Canvas(Modifier.fillMaxSize()) {
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ImmersiveLyrics(lyrics: Lyrics, positionSec: Float, durationSec: Int, onSeek: (Float) -> Unit, compact: Boolean = false) {
    val lines = lyrics.lines
    val current = if (lyrics.synced) lines.indexOfLast { it.timeSec >= 0 && it.timeSec <= positionSec } else -1
    val list = rememberLazyListState()
    var browsing by remember { mutableStateOf(false) }
    val wheel = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
    LaunchedEffect(list) {
        var settle: Job? = null
        merge(list.interactionSource.interactions.filterIsInstance<DragInteraction>(), wheel).collect { event ->
            settle?.cancel()
            browsing = true
            if (event !is DragInteraction.Start) settle = launch { delay(4500); browsing = false }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val focus = maxHeight * .26f
        LaunchedEffect(current, browsing, maxHeight) {
            if (!lyrics.synced || browsing) return@LaunchedEffect
            val target = current.coerceAtLeast(0)
            val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
            // item offsets exclude the top padding where the focus line sits
            if (item != null) list.animateScrollBy(item.offset.toFloat(), tween(650, easing = FastOutSlowInEasing))
            else list.animateScrollToItem(target)
        }
        LazyColumn(state = list,
            modifier = Modifier.fillMaxSize().onPointerEvent(PointerEventType.Scroll) { wheel.tryEmit(Unit) }
                .lyricsEdgeFade().padding(horizontal = if (compact) 24.dp else 28.dp),
            contentPadding = PaddingValues(top = if (lyrics.synced) focus else 32.dp,
                bottom = if (lyrics.synced) maxHeight * .7f else 48.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 22.dp),
        ) {
            items(lines.size) { index ->
                var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
                val seekable = lyrics.synced && durationSec > 0 && lines[index].timeSec >= 0
                val seekLine by rememberUpdatedState {
                    if (seekable) {
                        browsing = false
                        onSeek((lines[index].timeSec.toFloat() / durationSec).coerceIn(0f, 1f))
                    }
                }
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
                Text(lines[index].text.ifBlank { "•••" },
                    onTextLayout = { textLayout = it },
                    color = Color.White.copy(alpha = alpha), fontSize = if (compact) 22.sp else 30.sp,
                    lineHeight = if (compact) 29.sp else 38.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = (-.5).sp,
                    modifier = Modifier.fillMaxWidth().graphicsLayer {
                        scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, .5f)
                    }.blur(soften.dp).semantics {
                        selected = active
                        if (seekable) onClick { seekLine(); true }
                    }.padding(vertical = 6.dp).then(if (seekable) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
                        .pointerInput(seekable) {
                            detectTapGestures { point ->
                                val layout = textLayout
                                val line = layout?.getLineForVerticalPosition(point.y)
                                val onText = layout != null && line != null &&
                                    point.y >= layout.getLineTop(line) && point.y < layout.getLineBottom(line) &&
                                    point.x >= layout.getLineLeft(line) && point.x < layout.getLineRight(line)
                                if (onText && seekable) seekLine()
                            }
                        },
                )
            }
        }
        androidx.compose.animation.AnimatedVisibility(browsing && lyrics.synced,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) {
            Text(appString(R.string.text_back_to_current_line_d5146a), color = Color.White,
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = .5f))
                    .clickable(role = Role.Button) { browsing = false }.pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 20.dp, vertical = 14.dp))
        }
    }
}

private fun Modifier.lyricsEdgeFade() = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(Brush.verticalGradient(0f to Color.Transparent, .07f to Color.Black,
            .9f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
    }
