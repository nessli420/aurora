package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun PermissionsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }

    // re-read live perm state on resume from system settings
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    refresh // recompute statuses on each bump
    val audioPerm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val notifOk = if (Build.VERSION.SDK_INT >= 33) NotificationManagerCompat.from(ctx).areNotificationsEnabled() else true
    val audioOk = ContextCompat.checkSelfPermission(ctx, audioPerm) == PackageManager.PERMISSION_GRANTED
    val batteryOk = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)
    val exactOk = if (Build.VERSION.SDK_INT >= 31) (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms() else true
    val fsOk = if (Build.VERSION.SDK_INT >= 34) (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).canUseFullScreenIntent() else true

    val usbDev = remember(refresh) { com.decent.usbaudio.UsbAudioDevice.getInstance(ctx) }
    val dac = remember(refresh) { usbDev.findUsbAudioDevice() }
    val dacOk = dac != null && usbDev.hasPermission(dac)

    fun open(action: String, withPackage: Boolean = false) {
        runCatching {
            val i = Intent(action)
            if (withPackage) i.data = Uri.parse("package:" + ctx.packageName)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(appString(R.string.text_permissions_d06d55), onBack)
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
        ) {
            item {
                Text(
                    appString(R.string.text_grant_what_you_use_aurora_works_without_any_of_these_but_each_unl_7a03fb),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
                )
            }
            item {
                PermRow(Icons.Filled.Notifications, appString(R.string.text_notifications_753a22), appString(R.string.text_now_playing_controls_downloads_alarms_1b631c), notifOk) {
                    if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else open(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                }
            }
            item {
                PermRow(Icons.Filled.LibraryMusic, appString(R.string.text_on_device_music_bac4c1), appString(R.string.text_read_local_audio_for_the_library_playback_sonic_analysis_5d975b), audioOk) {
                    audioLauncher.launch(audioPerm)
                }
            }
            item {
                PermRow(Icons.Filled.BatteryStd, appString(R.string.text_ignore_battery_optimization_77313f), appString(R.string.text_keep_scanning_playback_running_in_the_background_2e56ee), batteryOk) {
                    open(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, withPackage = true)
                }
            }
            item {
                PermRow(Icons.Filled.Alarm, appString(R.string.text_exact_alarms_cd19fa), appString(R.string.text_fire_the_wake_to_music_alarm_at_the_precise_time_bbac52), exactOk) {
                    if (Build.VERSION.SDK_INT >= 31) open(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                }
            }
            item {
                PermRow(Icons.Filled.Fullscreen, appString(R.string.text_full_screen_alarm_a37b1c), appString(R.string.text_show_the_alarm_full_screen_over_the_lock_screen_1d1cfb), fsOk) {
                    if (Build.VERSION.SDK_INT >= 34) open(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, withPackage = true)
                }
            }
            item {
                val sub = when {
                    dac == null -> appString(R.string.text_no_usb_dac_connected_446f5b)
                    dacOk -> appString(R.string.text_access_granted_for_8fb83b, (dac.productName ?: appString(R.string.text_the_dac_05159b)))
                    else -> appString(R.string.text_tap_to_allow_bit_perfect_access_to_c08d14, (dac.productName ?: appString(R.string.text_the_dac_05159b)))
                }
                PermRow(Icons.Filled.Usb, appString(R.string.text_usb_dac_948bb8), sub, dacOk, enabled = dac != null) {
                    dac?.let { usbDev.requestPermission(it) { refresh++ } }
                }
            }
            item {
                Text(
                    appString(R.string.text_android_can_t_grant_a_usb_device_permanently_without_a_per_plug_p_706fd2) +
                        appString(R.string.text_may_re_ask_on_reconnect_aurora_re_requests_automatically_when_bit_23facf),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun PermRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    granted: Boolean,
    enabled: Boolean = true,
    onGrant: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = enabled && !granted, onClick = onGrant)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(10.dp))
        if (granted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(appString(R.string.text_granted_43b11e), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        } else if (enabled) {
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onGrant).padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(appString(R.string.text_grant_c02329), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}
