package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

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
        SettingsTopBar(appString(R.string.text_gestures_behaviour_9d13b8), onBack)
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
            item { SettingsSectionTitle(appString(R.string.text_player_gestures_1c6f88)) }
            item {
                SettingsGroup {
                    SettingsSwitchRow(Icons.Filled.Swipe, appString(R.string.text_swipe_artwork_to_change_track_7a762f), appString(R.string.text_swipe_the_player_artwork_left_right_fd3460), gestures.swipeArtwork) { v -> scope.launch { store.setGestureSwipeArtwork(v) } }
                    SettingsRowDivider()
                    SettingsSwitchRow(Icons.Filled.SwipeDown, appString(R.string.text_swipe_down_to_dismiss_575cb1), appString(R.string.text_pull_down_on_the_player_sheets_to_close_them_36e776), gestures.swipeDownDismiss) { v -> scope.launch { store.setGestureSwipeDismiss(v) } }
                    SettingsRowDivider()
                    SettingsSwitchRow(Icons.Filled.TouchApp, appString(R.string.text_double_tap_to_play_pause_d5e7e4), appString(R.string.text_double_tap_the_player_artwork_9ef287), gestures.doubleTapPause) { v -> scope.launch { store.setGestureDoubleTap(v) } }
                }
            }

            item { SettingsSectionTitle(appString(R.string.text_feedback_c8d767)) }
            item {
                SettingsGroup {
                    SettingsSwitchRow(Icons.Filled.Vibration, appString(R.string.text_haptic_feedback_b0baa9), appString(R.string.text_subtle_vibration_on_play_skip_navigation_d6940c), haptics) { v -> scope.launch { store.setHaptics(v) } }
                }
            }

            item { SettingsSectionTitle(appString(R.string.text_behaviour_171ca0)) }
            item {
                SettingsGroup {
                    SettingsSwitchRow(Icons.Filled.Notifications, appString(R.string.text_push_notifications_03be2f), appString(R.string.text_new_releases_recommendations_02976f), notifications) { notifications = it }
                    SettingsRowDivider()
                    SettingsSwitchRow(Icons.Filled.Radio, appString(R.string.text_autoplay_radio_7fa244), appString(R.string.text_keep_playing_similar_tracks_when_the_queue_ends_172967), playback.autoplayRadio) { v -> scope.launch { store.setAutoplayRadio(v) } }
                    SettingsRowDivider()
                    SettingsSwitchRow(Icons.Filled.History, appString(R.string.text_scrobble_3d49e2), appString(R.string.text_report_plays_to_your_server_262da5), playback.scrobble) { v -> scope.launch { store.setScrobble(v) } }
                    SettingsRowDivider()
                    SettingsSwitchRow(Icons.Filled.Lock, appString(R.string.text_private_session_ec7cef), appString(R.string.text_don_t_report_plays_to_your_server_or_last_fm_074883), privateSession) { v -> scope.launch { store.setPrivateSession(v) } }
                }
            }
        }
    }
}
