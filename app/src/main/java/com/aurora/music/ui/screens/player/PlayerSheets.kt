package com.aurora.music.ui.screens.player

import com.aurora.music.localization.appString
import com.aurora.music.ui.theme.paperClip
import com.aurora.music.R

import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurora.music.AuroraApplication
import com.aurora.music.ui.screens.settings.NetworkOutputControls
import com.aurora.music.ui.theme.Motion

private data class OutputDevice(val id: Int, val label: String, val icon: ImageVector)

@Composable
fun PlayerCastButton(modifier: Modifier = Modifier) {
    var show by remember { mutableStateOf(false) }
    Icon(
        Icons.Filled.Cast, appString(R.string.text_cast_60745a),
        modifier = modifier.paperClip(CircleShape).clickable { show = true }.padding(8.dp),
    )
    if (show) CastSheet(onDismiss = { show = false })
}

@Composable
private fun CastRow() {
    var show by remember { mutableStateOf(false) }
    ConnectRow(Icons.Filled.Cast, appString(R.string.text_cast_to_a_device_1c0bd6), "Chromecast · DLNA · Aurora", false) { show = true }
    if (show) CastSheet(onDismiss = { show = false })
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CastSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val network = (context.applicationContext as AuroraApplication).container.networkOutput
    val router = remember { androidx.mediarouter.media.MediaRouter.getInstance(context.applicationContext) }
    val selector = remember {
        androidx.mediarouter.media.MediaRouteSelector.Builder()
            .addControlCategory(
                com.google.android.gms.cast.CastMediaControlIntent.categoryForCast(
                    com.google.android.gms.cast.CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID,
                ),
            )
            .build()
    }
    var routes by remember { mutableStateOf(emptyList<androidx.mediarouter.media.MediaRouter.RouteInfo>()) }
    var selectedId by remember { mutableStateOf(router.selectedRoute.id) }

    androidx.compose.runtime.DisposableEffect(Unit) {
        fun refresh() {
            routes = router.routes.filter { it.matchesSelector(selector) }
            selectedId = router.selectedRoute.id
        }
        val cb = object : androidx.mediarouter.media.MediaRouter.Callback() {
            override fun onRouteAdded(r: androidx.mediarouter.media.MediaRouter, route: androidx.mediarouter.media.MediaRouter.RouteInfo) = refresh()
            override fun onRouteRemoved(r: androidx.mediarouter.media.MediaRouter, route: androidx.mediarouter.media.MediaRouter.RouteInfo) = refresh()
            override fun onRouteChanged(r: androidx.mediarouter.media.MediaRouter, route: androidx.mediarouter.media.MediaRouter.RouteInfo) = refresh()
            override fun onRouteSelected(r: androidx.mediarouter.media.MediaRouter, route: androidx.mediarouter.media.MediaRouter.RouteInfo, reason: Int) = refresh()
            override fun onRouteUnselected(r: androidx.mediarouter.media.MediaRouter, route: androidx.mediarouter.media.MediaRouter.RouteInfo, reason: Int) = refresh()
        }
        router.addCallback(selector, cb, androidx.mediarouter.media.MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        refresh()
        onDispose { router.removeCallback(cb) }
    }

    val casting = router.selectedRoute.id != router.defaultRoute.id
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp).padding(bottom = 28.dp)) {
            Text(appString(R.string.text_play_on_a_device_d66db5), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, modifier = Modifier.padding(start = 16.dp))
            AnimatedVisibility(routes.isEmpty(), enter = fadeIn(Motion.enter()) + expandVertically(Motion.enter()), exit = fadeOut(Motion.exit()) + shrinkVertically(Motion.exit())) {
                Text(appString(R.string.text_searching_for_chromecast_devices_13fdbb), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
            routes.forEach { route -> ConnectRow(Icons.Filled.Cast, route.name, null, route.id == selectedId) { route.select(); onDismiss() } }
            if (casting) ConnectRow(Icons.Filled.Speaker, appString(R.string.text_stop_casting_1f7a2c), null, false) {
                router.unselect(androidx.mediarouter.media.MediaRouter.UNSELECT_REASON_STOPPED); onDismiss()
            }
            NetworkOutputControls(network, onConnected = onDismiss)
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun OutputDeviceSheet(currentId: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val devices = remember {
        val am = context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        val list = mutableListOf(OutputDevice(0, appString(R.string.text_automatic_ac9041), Icons.Filled.Check))
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type in USEFUL_OUTPUT_TYPES }
            .forEach { list.add(OutputDevice(it.id, outputLabel(it), deviceIcon(it.type))) }
        list
    }
    val controlling = (context.applicationContext as com.aurora.music.AuroraApplication).container.networkOutput.state
        .collectAsStateWithLifecycle().value.controlling != null
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp).padding(bottom = 28.dp)) {
            Text(appString(R.string.text_play_on_4bd6fc), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
            ConnectDevicesSection(onDismiss)
            if (!controlling) CastRow()
            if (!controlling) devices.forEach { d -> ConnectRow(d.icon, d.label, null, d.id == currentId) { onSelect(d.id); onDismiss() } }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SleepTimerSheet(
    currentMinutes: Int,
    endOfTrack: Boolean,
    onSelect: (Int) -> Unit,
    onEndOfTrack: () -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(0 to appString(R.string.text_off_e3de5a), 5 to appString(R.string.text_5_min_b45ebd), 15 to appString(R.string.text_15_min_1028d6), 30 to appString(R.string.text_30_min_d3ddf7), 45 to appString(R.string.text_45_min_983be7), 60 to appString(R.string.text_1_hour_f030c3))
    val status = when {
        endOfTrack -> appString(R.string.text_pausing_at_the_end_of_this_track_d79c45)
        currentMinutes > 0 -> appString(R.string.text_pausing_in_min_fades_out_741f18, (currentMinutes))
        else -> appString(R.string.text_pause_playback_after_a_set_time_ef485a)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Text(appString(R.string.text_sleep_timer_e90613), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, modifier = Modifier.padding(bottom = 4.dp))
            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                options.forEach { (min, label) -> TimerChip(label, !endOfTrack && min == currentMinutes) { onSelect(min); onDismiss() } }
                TimerChip(appString(R.string.text_end_of_track_9923e9), endOfTrack) { onEndOfTrack(); onDismiss() }
            }
        }
    }
}

@Composable
private fun TimerChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val fill by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh, Motion.state(), label = "timerFill")
    val ink by animateColorAsState(if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, Motion.state(), label = "timerInk")
    Box(Modifier.paperClip(RoundedCornerShape(50)).background(fill).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

internal val USEFUL_OUTPUT_TYPES = setOf(
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_HEARING_AID,
    AudioDeviceInfo.TYPE_DOCK,
)

internal fun outputLabel(d: AudioDeviceInfo): String {
    val product = d.productName?.toString()?.trim().orEmpty()
    return when (d.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> appString(R.string.text_phone_speaker_ae9329)
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> appString(R.string.text_wired_headphones_56e99c)
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY ->
            if (product.isNotBlank()) "USB · $product" else appString(R.string.text_usb_dac_948bb8)
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET -> product.ifBlank { "Bluetooth" }
        AudioDeviceInfo.TYPE_HEARING_AID -> appString(R.string.text_hearing_aid_375267)
        AudioDeviceInfo.TYPE_DOCK -> appString(R.string.text_dock_e8b5cb)
        else -> product.ifBlank { appString(R.string.text_output_4bed33) }
    }
}

private fun deviceIcon(type: Int): ImageVector = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_DOCK -> Icons.Filled.Speaker
    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> Icons.Filled.Usb
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID -> Icons.Filled.Bluetooth
    else -> Icons.Filled.Headphones
}
