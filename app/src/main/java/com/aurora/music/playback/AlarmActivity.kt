package com.aurora.music.playback

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.UiPrefs
import com.aurora.music.ui.theme.AuroraTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Full-screen wake-to-music alarm screen. Shows over the lock screen and turns the screen on; a
 * prominent Dismiss stops the music, and "Turn off daily alarm" also cancels the recurring schedule.
 */
class AlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }
        val container = (application as AuroraApplication).container
        setContent {
            val uiPrefs by container.settingsStore.uiPrefs.collectAsStateWithLifecycle(initialValue = UiPrefs())
            AuroraTheme(uiPrefs = uiPrefs) {
                AlarmDismissScreen(
                    onDismiss = { dismiss(disableDaily = false) },
                    onTurnOff = { dismiss(disableDaily = true) },
                )
            }
        }
    }

    private fun dismiss(disableDaily: Boolean) {
        runCatching {
            startService(Intent(this, PlaybackService::class.java).setAction(PlaybackService.ACTION_ALARM_DISMISS))
        }
        if (disableDaily) {
            val store = (application as AuroraApplication).container.settingsStore
            // Detached scope so the write survives finish(). Clearing the pref makes AppContainer
            // cancel the AlarmManager schedule.
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                val a = store.alarmPrefs.first()
                store.setAlarm(enabled = false, hour = a.hour, minute = a.minute)
            }
        }
        finish()
    }
}

@Composable
private fun AlarmDismissScreen(onDismiss: () -> Unit, onTurnOff: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.Alarm, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(20.dp))
            Text("Alarm", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(6.dp))
            Text(
                "Time to wake up",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(40.dp))
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.size(width = 220.dp, height = 56.dp),
            ) {
                Text("Dismiss", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onTurnOff) {
                Text("Turn off daily alarm", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
