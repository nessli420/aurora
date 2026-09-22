package com.aurora.music.ui.screens.player

import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.MaterialTheme
import coil.compose.AsyncImage
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.VideoSize

/** The service's player owns audio, DSP and video; the screen only attaches a surface. */
@Composable
fun PlaybackVideo(
    player: Player,
    artworkUrl: String,
    accent: Color,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 28.dp,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val texture = remember(context, player) { TextureView(context) }
    var videoSize by remember(player) { mutableStateOf(player.videoSize) }
    DisposableEffect(player, texture, owner) {
        fun attach() {
            player.setVideoTextureView(texture)
            texture.keepScreenOn = player.isPlaying
        }
        fun detach() {
            player.clearVideoTextureView(texture)
            texture.keepScreenOn = false
        }
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) { videoSize = size }
            override fun onIsPlayingChanged(isPlaying: Boolean) { texture.keepScreenOn = isPlaying && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) attach()
            if (event == Lifecycle.Event.ON_STOP) detach()
        }
        player.addListener(listener)
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) attach()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            detach()
        }
    }
    val ratio = if (videoSize.width > 0 && videoSize.height > 0) videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height else 16f / 9f
    BoxWithConstraints(modifier.clip(RoundedCornerShape(cornerRadius))) {
        Box(Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(accent.copy(alpha = .38f), MaterialTheme.colorScheme.surface, Color.Black))))
        if (artworkUrl.isNotBlank()) {
            AsyncImage(
                model = artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.12f; scaleY = 1.12f; alpha = .4f },
            )
        }
        Box(Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color.Black.copy(alpha = .12f), Color.Transparent, Color.Black.copy(alpha = .28f)))))
        val videoWidth = minOf(maxWidth, maxHeight * ratio)
        val videoHeight = minOf(maxHeight, maxWidth / ratio)
        AndroidView(
            factory = { texture },
            modifier = Modifier.align(Alignment.Center).size(videoWidth, videoHeight),
        )
    }
}
