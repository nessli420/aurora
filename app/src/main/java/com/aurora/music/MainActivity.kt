package com.aurora.music

import android.content.Intent
import android.app.SearchManager
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.data.UiPrefs
import com.aurora.music.ui.AuroraApp
import com.aurora.music.ui.theme.AuroraTheme
import com.aurora.music.playback.MediaSearchRequest
import com.aurora.music.viewmodel.PlayerViewModel

class MainActivity : ComponentActivity() {
    private val playerViewModel: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as AuroraApplication).container
        handleAuthRedirect(intent)
        if (savedInstanceState == null) handleMediaSearch(intent)
        setContent {
            val uiPrefs by container.settingsStore.uiPrefs.collectAsStateWithLifecycle(initialValue = UiPrefs())
            AuroraTheme(uiPrefs = uiPrefs) {
                AuroraApp()
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
