package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material.icons.filled.SwipeDown
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.GesturePrefs
import com.aurora.music.data.PlaybackPrefs
import kotlinx.coroutines.launch

@Composable
fun GesturesSettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val store = container.settingsStore
    val gestures by store.gesturePrefs.collectAsStateWithLifecycle(initialValue = GesturePrefs())
    val haptics by store.haptics.collectAsStateWithLifecycle(initialValue = false)
    val privateSession by store.privateSession.collectAsStateWithLifecycle(initialValue = false)
    val playback by store.playbackPrefs.collectAsStateWithLifecycle(initialValue = PlaybackPrefs())
    var notifications by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar("Gestures & behaviour", onBack)
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
            item { SettingsSectionTitle("Player gestures") }
            item {
                SettingsGroup {
                    SettingsSwitchRow(Icons.Filled.Swipe, "Swipe artwork to change track", "Swipe the player artwork left / right", gestures.swipeArtwork) { v -> scope.launch { store.setGestureSwipeArtwork(v) } }
                    SettingsRowDivider()
                    SettingsSwitchRow(Icons.Filled.SwipeDown, "Swipe down to dismiss", "Pull down on the player & sheets to close them", gestures.swipeDownDismiss) { v -> scope.launch { store.setGestureSwipeDismiss(v) } }
                    SettingsRowDivider()
                    SettingsSwitchRow(Icons.Filled.TouchApp, "Double-tap to play / pause", "Double-tap the player artwork", gestures.doubleTapPause) { v -> scope.launch { store.setGestureDoubleTap(v) } }
                }
            }

            item { SettingsSectionTitle("Feedback") }
            item {
                SettingsGroup {
                    SettingsSwitchRow(Icons.Filled.Vibration, "Haptic feedback", "Subtle vibration on play, skip & navigation", haptics) { v -> scope.launch { store.setHaptics(v) } }
                }
            }

            item { SettingsSectionTitle("Behaviour") }
            item {
                SettingsGroup {
                    SettingsSwitchRow(Icons.Filled.Notifications, "Push notifications", "New releases & recommendations", notifications) { notifications = it }
                    SettingsRowDivider()
                    SettingsSwitchRow(Icons.Filled.Radio, "Autoplay radio", "Keep playing similar tracks when the queue ends", playback.autoplayRadio) { v -> scope.launch { store.setAutoplayRadio(v) } }
                    SettingsRowDivider()
                    SettingsSwitchRow(Icons.Filled.History, "Scrobble", "Report plays to your server", playback.scrobble) { v -> scope.launch { store.setScrobble(v) } }
                    SettingsRowDivider()
                    SettingsSwitchRow(Icons.Filled.Lock, "Private session", "Don't report plays to your server or Last.fm", privateSession) { v -> scope.launch { store.setPrivateSession(v) } }
                }
            }
        }
    }
}
