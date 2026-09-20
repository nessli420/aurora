package com.aurora.music.ui.screens.player

import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.VideoSize

/** The service's player owns audio, DSP and video; the screen only attaches a surface. */
@Composable
fun PlaybackVideo(player: Player, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val texture = remember(context, player) { TextureView(context) }
    var videoSize by remember(player) { mutableStateOf(player.videoSize) }
    DisposableEffect(player, texture, owner) {
        fun attach() {
            player.setVideoTextureView(texture)
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build()
            texture.keepScreenOn = player.isPlaying
        }
        fun detach() {
            player.clearVideoTextureView(texture)
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
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
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(factory = { texture }, modifier = Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = true).fillMaxSize())
    }
}
