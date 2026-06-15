package com.aurora.music.ui.screens.visualizer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.VisualizerPrefs
import com.aurora.music.data.VisualizerStyle
import com.aurora.music.data.VizBackground
import com.aurora.music.data.VizColor
import com.aurora.music.ui.components.Artwork
import com.aurora.music.util.rememberDominantColor
import com.aurora.music.viewmodel.PlayerUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun VisualizerScreen(state: PlayerUiState, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val container = remember { (ctx.applicationContext as AuroraApplication).container }
    val controller = container.visualizer
    val prefs by container.settingsStore.visualizerPrefs.collectAsStateWithLifecycle(initialValue = VisualizerPrefs())
    val scope = rememberCoroutineScope()
    val song = state.current
    val accent by rememberDominantColor(song.artworkUrl, song.accent)

    // run analyser only while screen visible
    DisposableEffect(Unit) {
        controller.start()
        onDispose { controller.stop() }
    }
    LaunchedEffect(prefs.barCount, prefs.fftSize, prefs.smoothing, prefs.sensitivity, prefs.minHz, prefs.maxHz, prefs.peakHold, prefs.fpsCap) {
        controller.applyPrefs(prefs)
    }

    val colors = remember(prefs.colorSource, prefs.primaryColor, prefs.secondaryColor, accent) {
        when (prefs.colorSource) {
            VizColor.CUSTOM -> VizColors(Color(prefs.primaryColor), Color(prefs.primaryColor))
            VizColor.GRADIENT -> VizColors(Color(prefs.primaryColor), Color(prefs.secondaryColor))
            VizColor.ALBUM_ART -> VizColors(accent, lerp(accent, Color.White, 0.4f))
            else -> VizColors(accent, lerp(accent, Color.White, 0.35f))
        }
    }
    var controlsVisible by remember { mutableStateOf(true) }
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) { delay(4500); controlsVisible = false }
    }

    val isRadial = prefs.style == VisualizerStyle.RADIAL_BARS || prefs.style == VisualizerStyle.RADIAL_WAVE ||
        prefs.style == VisualizerStyle.COMBO || prefs.style == VisualizerStyle.FLUID ||
        prefs.style == VisualizerStyle.ORB || prefs.style == VisualizerStyle.RINGS ||
        prefs.style == VisualizerStyle.CONSTELLATION

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { controlsVisible = !controlsVisible },
    ) {
        when (prefs.background) {
            VizBackground.ALBUM_BLUR -> if (song.artworkUrl.isNotBlank()) {
                Artwork(song.artworkUrl, accent, Modifier.fillMaxSize().blur(60.dp), corner = 0.dp)
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
            } else {
                Box(Modifier.fillMaxSize().background(bgGradient(accent)))
            }
            VizBackground.GRADIENT -> Box(Modifier.fillMaxSize().background(bgGradient(if (prefs.colorSource == VizColor.GRADIENT) Color(prefs.primaryColor) else colors.primary)))
            else -> {}
        }

        if (isRadial && prefs.showAlbumArt && song.artworkUrl.isNotBlank()) {
            Artwork(
                song.artworkUrl, accent,
                Modifier.align(Alignment.Center).size(120.dp).clip(CircleShape),
                corner = 60.dp,
            )
        }

        VisualizerCanvas(
            controller, prefs, colors,
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(16.dp),
        )

        AnimatedVisibility(controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopStart)) {
            Row(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.systemBars).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Close, "Close",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClose).background(Color.White.copy(alpha = 0.12f)).padding(8.dp),
                )
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(song.title.ifBlank { "Aurora" }, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    if (song.artist.isNotBlank()) Text(song.artist, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        AnimatedVisibility(controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            LazyRow(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.systemBars).padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                items((0 until VisualizerStyle.count).toList()) { s ->
                    val selected = s == prefs.style
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) colors.primary else Color.White.copy(alpha = 0.14f))
                            .clickable { scope.launch { container.settingsStore.setVisualizer(prefs.copy(style = s)) } }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                    ) {
                        Text(
                            VisualizerStyle.label(s),
                            color = if (selected) Color.Black else Color.White,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }

    BackHandler { onClose() }
}

private fun bgGradient(c: Color): Brush = Brush.verticalGradient(
    listOf(c.copy(alpha = 0.35f), c.copy(alpha = 0.08f), Color.Black, Color.Black),
)
