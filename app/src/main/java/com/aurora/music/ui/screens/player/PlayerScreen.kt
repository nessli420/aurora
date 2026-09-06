package com.aurora.music.ui.screens.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurora.music.data.MockData
import com.aurora.music.model.LyricLine
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import com.aurora.music.data.SeekStyle
import com.aurora.music.data.ThemeStyle
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.Waveform
import com.aurora.music.ui.components.formatTime
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.auroraBackdrop
import com.aurora.music.ui.theme.auroraPanel
import com.aurora.music.viewmodel.PlayerUiState
import com.aurora.music.viewmodel.RepeatMode

@Composable
fun PlayerScreen(
    state: PlayerUiState,
    onCollapse: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Float) -> Unit,
    onToggleLike: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onOpenSpeedPitch: () -> Unit,
    onOpenQueue: () -> Unit,
    onGoToAlbum: () -> Unit,
    onGoToArtist: () -> Unit,
    onOpenOutput: () -> Unit,
    onOpenSleep: () -> Unit,
    onOpenVisualizer: () -> Unit,
    onSonicRadio: () -> Unit,
    onAutoDj: () -> Unit,
    gestures: com.aurora.music.data.GesturePrefs = com.aurora.music.data.GesturePrefs(),
) {
    val song = state.current
    val ui = LocalUiPrefs.current
    val classic = ui.themeStyle == ThemeStyle.AURORA
    var showLyrics by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val accent by com.aurora.music.util.rememberDominantColor(song.artworkUrl, song.accent)
    val playerAccent = if (classic) accent else MaterialTheme.colorScheme.primary
    val onPlayerAccent = if (classic) {
        if (accent.luminance() > 0.6f) Color.Black else Color.White
    } else MaterialTheme.colorScheme.onPrimary
    val g = ui.playerGradient
    val bg = Brush.verticalGradient(
        listOf(
            accent.copy(alpha = (0.65f * g).coerceIn(0f, 1f)),
            accent.copy(alpha = (0.22f * g).coerceIn(0f, 1f)),
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.background,
        )
    )

    // player is outside the scaffold so LocalContentColor defaults to black provide it explicitly
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    var dragAccum by remember { mutableStateOf(0f) }
    Box(
        Modifier
            .fillMaxSize()
            .then(
                if (classic) Modifier.background(MaterialTheme.colorScheme.background).background(bg)
                else Modifier.auroraBackdrop()
            )
            // consume taps so nothing leaks through to the app behind
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) {}
            .then(
                if (gestures.swipeDownDismiss) Modifier.pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = { if (dragAccum > 150f) onCollapse(); dragAccum = 0f },
                        onDragCancel = { dragAccum = 0f },
                        onVerticalDrag = { _, dy -> if (dy > 0f) dragAccum += dy },
                    )
                } else Modifier
            ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 20.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { _, dragAmount ->
                            if (dragAmount > 24f) onCollapse()
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .width(40.dp).height(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)),
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown, "Collapse",
                        modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onCollapse).padding(6.dp),
                    )
                    // balances trailing icons so PLAYING FROM stays centered
                    Spacer(Modifier.width(80.dp))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("PLAYING FROM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), maxLines = 1)
                        Text(song.album.ifBlank { "Aurora" }, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, color = MaterialTheme.colorScheme.onSurface)
                    }
                    // cast route picker tvs/chromecast show here not in the local-output sheet
                    PlayerCastButton(Modifier.size(40.dp))
                    Icon(
                        Icons.Filled.Speaker, "Output device",
                        modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onOpenOutput).padding(8.dp),
                    )
                    Box {
                        Icon(
                            Icons.Filled.MoreVert, "More",
                            modifier = Modifier.size(40.dp).clip(CircleShape).clickable { showMenu = true }.padding(8.dp),
                        )
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Sonic radio") },
                                onClick = { showMenu = false; onSonicRadio() },
                                leadingIcon = { Icon(Icons.Filled.Radio, null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Auto-DJ") },
                                onClick = { showMenu = false; onAutoDj() },
                                leadingIcon = { Icon(Icons.Filled.AutoAwesome, null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Visualizer") },
                                onClick = { showMenu = false; onOpenVisualizer() },
                                leadingIcon = { Icon(Icons.Filled.GraphicEq, null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Sleep timer") },
                                onClick = { showMenu = false; onOpenSleep() },
                                leadingIcon = { Icon(Icons.Filled.Bedtime, null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Go to album") },
                                enabled = song.albumId.isNotBlank(),
                                onClick = { showMenu = false; onGoToAlbum() },
                                leadingIcon = { Icon(Icons.Filled.Album, null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Go to artist") },
                                enabled = song.artistId.isNotBlank(),
                                onClick = { showMenu = false; onGoToArtist() },
                                leadingIcon = { Icon(Icons.Filled.Person, null) },
                            )
                            DropdownMenuItem(
                                text = { Text("View queue") },
                                onClick = { showMenu = false; onOpenQueue() },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .then(
                        if (gestures.swipeArtwork) Modifier.pointerInput(song.id) {
                            var dx = 0f
                            detectHorizontalDragGestures(
                                onDragEnd = { if (dx < -60f) onNext() else if (dx > 60f) onPrevious(); dx = 0f },
                                onDragCancel = { dx = 0f },
                                onHorizontalDrag = { _, amount -> dx += amount },
                            )
                        } else Modifier
                    )
                    .then(
                        if (gestures.doubleTapPause) Modifier.pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = { onTogglePlay() })
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = showLyrics,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "artVsLyrics",
                ) { lyrics ->
                    if (lyrics) {
                        LyricsPanel(song = song, positionSec = state.positionSec, accent = playerAccent, onSeek = onSeek, durationSec = state.durationSec)
                    } else {
                        val artModifier = Modifier.fillMaxWidth(ui.playerArtSize.coerceIn(0.5f, 1f)).aspectRatio(1f)
                        if (classic) {
                            Artwork(song.artworkUrl, song.accent, artModifier, corner = 20.dp)
                        } else {
                            Box(artModifier.auroraPanel(MaterialTheme.shapes.large, emphasized = true).padding(6.dp)) {
                                Artwork(song.artworkUrl, song.accent, Modifier.fillMaxSize(), corner = 20.dp)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.isPlaying) {
                    com.aurora.music.ui.components.LottieEqualizer(
                        modifier = Modifier.size(28.dp),
                        isPlaying = true,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.headlineMedium, fontWeight = if (classic) FontWeight.Bold else MaterialTheme.typography.headlineMedium.fontWeight, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text(song.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (state.bpm > 0) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "${state.keyName} · ${state.camelot} · ${state.bpm} BPM",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary, maxLines = 1,
                        )
                    }
                }
                val likeTint by animateColorAsState(
                    if (state.isCurrentLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, label = "like",
                )
                Icon(
                    imageVector = if (state.isCurrentLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = "Like",
                    tint = likeTint,
                    modifier = Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onToggleLike).padding(8.dp),
                )
            }

            val badge = formatBadge(song)
            val source = sourceLabel(song)
            if (badge.isNotEmpty() || source != null) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (source != null) {
                        Box(
                            Modifier.then(
                                if (classic) Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                                else Modifier.auroraPanel(MaterialTheme.shapes.extraSmall)
                            ).padding(horizontal = 10.dp, vertical = 3.dp),
                        ) { Text(source, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Spacer(Modifier.width(8.dp))
                    }
                    if (isLossless(song.suffix)) {
                        Box(
                            Modifier.clip(if (classic) RoundedCornerShape(50) else MaterialTheme.shapes.extraSmall).background(playerAccent).padding(horizontal = 8.dp, vertical = 3.dp),
                        ) { Text("LOSSLESS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, color = onPlayerAccent) }
                        Spacer(Modifier.width(8.dp))
                    }
                    if (badge.isNotEmpty()) {
                        Box(
                            Modifier.then(
                                if (classic) Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                                else Modifier.auroraPanel(MaterialTheme.shapes.extraSmall)
                            ).padding(horizontal = 10.dp, vertical = 3.dp),
                        ) { Text(badge, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            SeekBar(
                progress = state.progress,
                positionSec = state.positionSec.toInt(),
                durationSec = state.durationSec,
                accent = playerAccent,
                seed = song.id.hashCode(),
                seekStyle = ui.playerSeekStyle,
                waveBars = ui.playerWaveBars,
                onSeek = onSeek,
            )

            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.fillMaxWidth().then(
                    if (classic) Modifier else Modifier.auroraPanel(MaterialTheme.shapes.extraLarge, emphasized = true)
                ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Shuffle, "Shuffle",
                    tint = if (state.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onToggleShuffle).padding(8.dp),
                )
                Icon(
                    Icons.Filled.SkipPrevious, "Previous",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(56.dp).clip(CircleShape).clickable(onClick = onPrevious).padding(6.dp),
                )
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(if (classic) CircleShape else MaterialTheme.shapes.large)
                        .background(playerAccent)
                        .clickable(onClick = onTogglePlay),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = "Play/pause",
                        tint = onPlayerAccent,
                        modifier = Modifier.size(36.dp),
                    )
                }
                Icon(
                    Icons.Filled.SkipNext, "Next",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(56.dp).clip(CircleShape).clickable(onClick = onNext).padding(6.dp),
                )
                Icon(
                    imageVector = if (state.repeat == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    contentDescription = "Repeat",
                    tint = if (state.repeat != RepeatMode.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onCycleRepeat).padding(8.dp),
                )
            }

            Spacer(Modifier.height(12.dp))

            if (ui.playerShowUtilities) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BottomUtil(Icons.Filled.Speed, "Speed ${"%.1f".format(state.speed)}x", onOpenSpeedPitch)
                    BottomUtil(
                        Icons.Filled.Lyrics,
                        "Lyrics",
                        { showLyrics = !showLyrics },
                        active = showLyrics,
                    )
                    BottomUtil(Icons.AutoMirrored.Filled.QueueMusic, "Queue", onOpenQueue)
                }
            } else {
                Spacer(Modifier.height(12.dp))
            }
        }
    }
    }
}

private fun sourceLabel(song: com.aurora.music.model.Song): String? = when {
    song.streamUrl.isBlank() -> null
    song.streamUrl.startsWith("content://") -> "Local"
    song.streamUrl.startsWith("file://") -> "Downloaded"
    else -> "Streaming"
}

private fun formatBadge(song: com.aurora.music.model.Song): String {
    val parts = mutableListOf<String>()
    if (song.suffix.isNotBlank()) parts.add(song.suffix.uppercase())
    if (song.sampleRateHz > 0) parts.add("%.1f kHz".format(song.sampleRateHz / 1000f))
    if (song.bitDepth > 0) parts.add("${song.bitDepth}-bit")
    if (song.bitrateKbps > 0) parts.add("${song.bitrateKbps} kbps")
    return parts.joinToString(" · ")
}

private fun isLossless(suffix: String): Boolean =
    suffix.lowercase() in setOf("flac", "alac", "wav", "aiff", "aif", "ape", "wv", "dsf", "dff", "m4a")

@Composable
private fun BottomUtil(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, active: Boolean = false) {
    Row(
        Modifier.then(
            if (LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA) Modifier.clip(RoundedCornerShape(50))
            else Modifier.auroraPanel(MaterialTheme.shapes.small)
        ).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, label, tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SeekBar(progress: Float, positionSec: Int, durationSec: Int, accent: Color, seed: Int, seekStyle: Int, waveBars: Int, onSeek: (Float) -> Unit) {
    Column {
        if (durationSec <= 0) {
            // live stream has no fixed length nothing to scrub
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(8.dp))
                Text("LIVE", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Black, color = accent)
                Spacer(Modifier.weight(1f))
                Text(formatTime(positionSec), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }
        if (seekStyle == SeekStyle.BAR) {
            Slider(
                value = progress.coerceIn(0f, 1f),
                onValueChange = onSeek,
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Waveform(
                progress = progress,
                accent = accent,
                onSeek = onSeek,
                seed = seed,
                barCount = waveBars.coerceIn(16, 120),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(positionSec), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatTime(durationSec), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LyricsPanel(song: com.aurora.music.model.Song, positionSec: Float, durationSec: Int, accent: Color, onSeek: (Float) -> Unit) {
    val container = (androidx.compose.ui.platform.LocalContext.current.applicationContext as com.aurora.music.AuroraApplication).container
    var lyrics by remember(song.id) { mutableStateOf<com.aurora.music.data.Lyrics?>(null) }
    var loading by remember(song.id) { mutableStateOf(true) }
    LaunchedEffect(song.id) {
        loading = true
        lyrics = if (song.id.isEmpty()) null else container.lyricsRepository.lyricsFor(song)
        loading = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .then(
                if (LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA) Modifier.clip(RoundedCornerShape(24.dp)).background(
                    Brush.verticalGradient(listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0.10f), MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)))
                ) else Modifier.auroraPanel(MaterialTheme.shapes.large)
            ),
    ) {
        val l = lyrics
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                com.aurora.music.ui.components.LottieLoader(modifier = Modifier.size(64.dp))
            }
            l == null || l.lines.isEmpty() -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Filled.Lyrics, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(10.dp))
                Text("No lyrics found", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Tried the server & LRCLIB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(12.dp).clip(RoundedCornerShape(50))
                        .background(accent.copy(alpha = 0.85f)).padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(if (l.synced) l.source else "${l.source} · text", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, color = if (accent.luminance() > 0.6f) Color.Black else Color.White)
                }
                if (l.synced) SyncedLyrics(l.lines, positionSec, durationSec, accent, onSeek)
                else PlainLyrics(l.lines)
            }
        }
    }
}

// fades the scroll edges without caring what's behind the panel
private fun Modifier.lyricsEdgeFade(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                0.09f to Color.Black,
                0.88f to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

@Composable
private fun SyncedLyrics(lines: List<LyricLine>, positionSec: Float, durationSec: Int, accent: Color, onSeek: (Float) -> Unit) {
    val currentIndex = remember(positionSec, lines) {
        lines.indexOfLast { it.timeSec in 0..positionSec.toInt() }.coerceAtLeast(0)
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // manual scrolling pauses the follow-cam so reading ahead doesn't fight the ticker
    var browsing by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        var settle: Job? = null
        listState.interactionSource.interactions.collect { i ->
            when (i) {
                is DragInteraction.Start -> { settle?.cancel(); browsing = true }
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    settle?.cancel()
                    settle = launch { delay(3500); browsing = false }
                }
            }
        }
    }
    LaunchedEffect(currentIndex, browsing) {
        if (!browsing) {
            val viewport = listState.layoutInfo.viewportSize.height
            val offset = if (viewport > 0) -(viewport / 3) else -300
            listState.animateScrollToItem(currentIndex.coerceAtLeast(0), scrollOffset = offset)
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().lyricsEdgeFade().padding(horizontal = 24.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 140.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(lines.size) { i ->
                val active = i == currentIndex
                val blank = lines[i].text.isBlank()
                val scale by animateFloatAsState(if (active) 1f else 0.92f, label = "lyricScale")
                // played lines fade back harder than the ones still coming
                val targetAlpha = when {
                    active -> 1f
                    i < currentIndex -> 0.26f
                    i == currentIndex + 1 -> 0.55f
                    else -> 0.38f
                }
                val lineAlpha by animateFloatAsState(targetAlpha, label = "lyricAlpha")
                Box(
                    Modifier.fillMaxWidth()
                        .graphicsLayer {
                            scaleX = scale; scaleY = scale
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                        }
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { if (durationSec > 0 && lines[i].timeSec >= 0) onSeek(lines[i].timeSec.toFloat() / durationSec) }
                        .padding(vertical = 4.dp),
                ) {
                    if (blank && active) {
                        PulsingDots(accent)
                    } else {
                        Text(
                            lines[i].text.ifBlank { "♪" },
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = if (active) FontWeight.Black else FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = lineAlpha),
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = browsing,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
        ) {
            val onAccent = if (accent.luminance() > 0.6f) Color.Black else Color.White
            Text(
                "Back to current line",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Black,
                color = onAccent,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(accent.copy(alpha = 0.92f))
                    .clickable {
                        browsing = false
                        scope.launch {
                            val viewport = listState.layoutInfo.viewportSize.height
                            listState.animateScrollToItem(currentIndex.coerceAtLeast(0), scrollOffset = if (viewport > 0) -(viewport / 3) else -300)
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun PulsingDots(accent: Color) {
    val t = rememberInfiniteTransition(label = "lyricDots")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
        repeat(3) { i ->
            val a by t.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(550, delayMillis = i * 180),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(Modifier.size(9.dp).clip(CircleShape).background(accent.copy(alpha = a)))
            if (i < 2) Spacer(Modifier.width(7.dp))
        }
    }
}

@Composable
private fun PlainLyrics(lines: List<LyricLine>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().lyricsEdgeFade().padding(horizontal = 24.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 52.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(lines.size) { i ->
            val text = lines[i].text
            if (text.isBlank()) {
                Spacer(Modifier.height(10.dp))
            } else {
                Text(
                    text,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = MaterialTheme.typography.titleMedium.lineHeight * 1.25f,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
                )
            }
        }
    }
}
