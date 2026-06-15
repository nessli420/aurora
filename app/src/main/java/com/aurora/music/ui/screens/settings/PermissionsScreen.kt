package com.aurora.music.ui.screens.settings

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
        SettingsTopBar("Permissions", onBack)
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
        ) {
            item {
                Text(
                    "Grant what you use. Aurora works without any of these, but each unlocks a feature.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
                )
            }
            item {
                PermRow(Icons.Filled.Notifications, "Notifications", "Now-playing controls, downloads & alarms", notifOk) {
                    if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else open(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                }
            }
            item {
                PermRow(Icons.Filled.LibraryMusic, "On-device music", "Read local audio for the library, playback & sonic analysis", audioOk) {
                    audioLauncher.launch(audioPerm)
                }
            }
            item {
                PermRow(Icons.Filled.BatteryStd, "Ignore battery optimization", "Keep scanning & playback running in the background", batteryOk) {
                    open(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, withPackage = true)
                }
            }
            item {
                PermRow(Icons.Filled.Alarm, "Exact alarms", "Fire the wake-to-music alarm at the precise time", exactOk) {
                    if (Build.VERSION.SDK_INT >= 31) open(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                }
            }
            item {
                PermRow(Icons.Filled.Fullscreen, "Full-screen alarm", "Show the alarm full-screen over the lock screen", fsOk) {
                    if (Build.VERSION.SDK_INT >= 34) open(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, withPackage = true)
                }
            }
            item {
                val sub = when {
                    dac == null -> "No USB DAC connected"
                    dacOk -> "Access granted for ${dac.productName ?: "the DAC"}"
                    else -> "Tap to allow bit-perfect access to ${dac.productName ?: "the DAC"}"
                }
                PermRow(Icons.Filled.Usb, "USB DAC", sub, dacOk, enabled = dac != null) {
                    dac?.let { usbDev.requestPermission(it) { refresh++ } }
                }
            }
            item {
                Text(
                    "Android can't grant a USB device permanently without a per-plug prompt, so the DAC " +
                        "may re-ask on reconnect — Aurora re-requests automatically when bit-perfect is on.",
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
                Text("Granted", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        } else if (enabled) {
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onGrant).padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text("Grant", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}
