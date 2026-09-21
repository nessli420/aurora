package com.aurora.music.ui.screens.player

import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import androidx.core.view.WindowCompat
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import com.aurora.music.data.Lyrics
import com.aurora.music.localization.appString
import com.aurora.music.model.Song
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.formatTime
import com.aurora.music.viewmodel.PlayerUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

@Composable
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
    DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        val lightStatus = bars?.isAppearanceLightStatusBars
        val lightNavigation = bars?.isAppearanceLightNavigationBars
        bars?.isAppearanceLightStatusBars = false
        bars?.isAppearanceLightNavigationBars = false
        onDispose {
            if (lightStatus != null) bars.isAppearanceLightStatusBars = lightStatus
            if (lightNavigation != null) bars.isAppearanceLightNavigationBars = lightNavigation
        }
    }
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    var lyrics by remember(song.id, song.playbackSource?.providerId) { mutableStateOf<Lyrics?>(null) }
    var loading by remember(song.id, song.playbackSource?.providerId) { mutableStateOf(true) }
    LaunchedEffect(song.id, song.playbackSource?.providerId) {
        lyrics = if (song.id.isBlank()) null else container.lyricsRepository.lyricsFor(song)
        loading = false
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF17191D)).clickable(
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
        indication = null,
    ) {}) {
        LyricsBackdrop(song)
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Artwork(song.artworkUrl, song.accent, Modifier.size(44.dp), corner = 10.dp)
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(song.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, color = Color.White.copy(alpha = .65f), fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onClose) {
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
                        ImmersiveLyrics(content, state.positionSec, state.durationSec, onSeek)
                    }
                }
            }
            LyricsTransport(state, lyrics?.source, onTogglePlay, onPrevious, onNext, onSeek)
        }
    }
}

// Extract several real artwork tones; app accents and Material You never enter this palette.
@Composable
private fun LyricsBackdrop(song: Song) {
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

@Composable
private fun ImmersiveLyrics(lyrics: Lyrics, positionSec: Float, durationSec: Int, onSeek: (Float) -> Unit) {
    val lines = lyrics.lines
    val current = if (lyrics.synced) lines.indexOfLast { it.timeSec >= 0 && it.timeSec <= positionSec } else -1
    val list = rememberLazyListState()
    var browsing by remember { mutableStateOf(false) }
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
            modifier = Modifier.fillMaxSize().lyricsEdgeFade().padding(horizontal = 28.dp),
            contentPadding = PaddingValues(top = if (lyrics.synced) focus else 32.dp,
                bottom = if (lyrics.synced) maxHeight * .7f else 48.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            items(lines.size) { index ->
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
                    if (lyrics.synced && !browsing && abs(index - current) > 1) .7f else 0f,
                    tween(450), label = "lineSoftness")
                Text(lines[index].text.ifBlank { "•••" },
                    color = Color.White.copy(alpha = alpha), fontSize = 30.sp, lineHeight = 38.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = (-.5).sp,
                    modifier = Modifier.fillMaxWidth().graphicsLayer {
                        scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, .5f)
                    }.blur(soften.dp).semantics { selected = active }
                        .clip(RoundedCornerShape(8.dp)).clickable(
                            enabled = lyrics.synced && durationSec > 0 && lines[index].timeSec >= 0,
                            role = Role.Button,
                        ) {
                            browsing = false
                            onSeek((lines[index].timeSec.toFloat() / durationSec).coerceIn(0f, 1f))
                        }.padding(vertical = 6.dp),
                )
            }
        }
        androidx.compose.animation.AnimatedVisibility(browsing && lyrics.synced,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) {
            Text(appString(R.string.text_back_to_current_line_d5146a), color = Color.White,
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = .5f))
                    .clickable(role = Role.Button) { browsing = false }
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

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun LyricsTransport(state: PlayerUiState, source: String?, onTogglePlay: () -> Unit,
    onPrevious: () -> Unit, onNext: () -> Unit, onSeek: (Float) -> Unit) {
    var seeking by remember(state.current.id) { mutableStateOf<Float?>(null) }
    val progress = seeking ?: if (state.durationSec > 0) state.positionSec / state.durationSec else 0f
    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp).padding(bottom = 12.dp)) {
        if (state.durationSec > 0) {
            Slider(value = progress.coerceIn(0f, 1f), onValueChange = { seeking = it },
                onValueChangeFinished = { seeking?.let(onSeek); seeking = null },
                thumb = { Box(Modifier.size(width = 3.dp, height = 16.dp).background(Color.White, CircleShape)) },
                track = { slider ->
                    Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = .2f))) {
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
            IconButton(onPrevious, Modifier.size(56.dp)) {
                Icon(Icons.Default.SkipPrevious, appString(R.string.text_previous_50f942), Modifier.size(30.dp), tint = Color.White)
            }
            Spacer(Modifier.width(24.dp))
            IconButton(onTogglePlay, Modifier.size(60.dp).background(Color.White.copy(alpha = .14f), CircleShape)) {
                Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    appString(R.string.text_play_pause_14a1d0), Modifier.size(34.dp), tint = Color.White)
            }
            Spacer(Modifier.width(24.dp))
            IconButton(onNext, Modifier.size(56.dp)) {
                Icon(Icons.Default.SkipNext, appString(R.string.text_next_bc9819), Modifier.size(30.dp), tint = Color.White)
            }
        }
        if (!source.isNullOrBlank()) Text(source, color = Color.White.copy(alpha = .45f), fontSize = 10.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
    }
}
