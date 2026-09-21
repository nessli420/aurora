package com.aurora.music

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.app.SearchManager
import android.os.Bundle
import android.provider.MediaStore
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.aurora.music.ui.layout.LocalWindowLayout
import com.aurora.music.ui.layout.WindowLayout
import com.aurora.music.ui.layout.isLargeDisplay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.data.UiPrefs
import com.aurora.music.ui.AuroraApp
import com.aurora.music.ui.theme.AuroraTheme
import com.aurora.music.playback.MediaSearchRequest
import com.aurora.music.viewmodel.PlayerViewModel

class MainActivity : AppCompatActivity() {
    private val playerViewModel: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val largeDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            isLargeDisplay(bounds.width(), bounds.height(), resources.displayMetrics.density)
        } else resources.configuration.smallestScreenWidthDp >= 600
        requestedOrientation = if (largeDisplay) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        com.aurora.music.localization.AppStrings.useConfiguration(resources.configuration)
        val container = (application as AuroraApplication).container
        handleAuthRedirect(intent)
        if (savedInstanceState == null) handleMediaSearch(intent)
        setContent {
            val uiPrefs by container.settingsStore.uiPrefs.collectAsStateWithLifecycle(initialValue = UiPrefs())
            AuroraTheme(uiPrefs = uiPrefs) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalWindowLayout provides WindowLayout(maxWidth.value.toInt(), maxHeight.value.toInt())) {
                        AuroraApp()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthRedirect(intent)
        handleMediaSearch(intent)
    }

    private fun handleMediaSearch(intent: Intent?) {
        if (intent?.action != MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return
        val request = runCatching {
            MediaSearchRequest.parse(
                query = intent.getStringExtra(SearchManager.QUERY),
                focus = intent.getStringExtra(MediaStore.EXTRA_MEDIA_FOCUS),
                title = intent.getStringExtra(MediaStore.EXTRA_MEDIA_TITLE),
                artist = intent.getStringExtra(MediaStore.EXTRA_MEDIA_ARTIST),
                album = intent.getStringExtra(MediaStore.EXTRA_MEDIA_ALBUM),
                genre = intent.getStringExtra("android.intent.extra.genre"),
                playlist = intent.getStringExtra("android.intent.extra.playlist"),
            )
        }.getOrNull() ?: return
        playerViewModel.playFromSearch(request)
    }

    /** Deliver the Spotify OAuth code from `aurora://spotify?code=...` to the auth flow. */
    private fun handleAuthRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "aurora" && data.host == "spotify") {
            data.getQueryParameter("code")?.takeIf { it.isNotBlank() }?.let {
                (application as AuroraApplication).container.emitSpotifyRedirect(it)
            }
        }
    }
}
