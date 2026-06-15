package com.aurora.music.ui.screens.player

import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class OutputDevice(val id: Int, val label: String, val icon: ImageVector)

@Composable
fun PlayerCastButton(modifier: Modifier = Modifier) {
    var show by remember { mutableStateOf(false) }
    Icon(
        Icons.Filled.Cast, "Cast",
        modifier = modifier.clip(CircleShape).clickable { show = true }.padding(8.dp),
    )
    if (show) CastSheet(onDismiss = { show = false })
}

// casting hands receiver a plain url so dsp/effects dont travel
@Composable
private fun CastRow() {
    var show by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { show = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Cast, null, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Cast to a device", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Text("Chromecast · effects & DSP off", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (show) CastSheet(onDismiss = { show = false })
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CastSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
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
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 28.dp)) {
            Text("Cast to a device", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, modifier = Modifier.padding(start = 16.dp))
            Text("Effects & DSP are off while casting", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
            if (routes.isEmpty()) {
                Text("Searching for devices…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
            routes.forEach { route ->
                val selected = route.id == selectedId
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { route.select(); onDismiss() }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Cast, null, tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(route.name, style = MaterialTheme.typography.titleSmall, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    if (selected) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            if (casting) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable {
                        router.unselect(androidx.mediarouter.media.MediaRouter.UNSELECT_REASON_STOPPED); onDismiss()
                    }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Speaker, null, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Text("Stop casting", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun OutputDeviceSheet(currentId: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val devices = remember {
        val am = context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        val list = mutableListOf(OutputDevice(0, "Automatic", Icons.Filled.Check))
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type in USEFUL_TYPES }
            .forEach { list.add(OutputDevice(it.id, deviceLabel(it), deviceIcon(it.type))) }
        list
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 28.dp)) {
            Text("Play on", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
            CastRow()
            devices.forEach { d ->
                val selected = d.id == currentId
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onSelect(d.id); onDismiss() }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                        Icon(d.icon, null, tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(d.label, style = MaterialTheme.typography.titleSmall, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    if (selected) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
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
    val options = listOf(0 to "Off", 5 to "5 min", 15 to "15 min", 30 to "30 min", 45 to "45 min", 60 to "1 hour")
    val status = when {
        endOfTrack -> "Pausing at the end of this track"
        currentMinutes > 0 -> "Pausing in $currentMinutes min · fades out"
        else -> "Pause playback after a set time"
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
            Text("Sleep timer", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, modifier = Modifier.padding(bottom = 4.dp))
            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                options.forEach { (min, label) ->
                    val selected = !endOfTrack && min == currentMinutes
                    Box(
                        Modifier.clip(RoundedCornerShape(50))
                            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { onSelect(min); onDismiss() }.padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                    }
                }
                Box(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(if (endOfTrack) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { onEndOfTrack(); onDismiss() }.padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text("End of track", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = if (endOfTrack) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

private val USEFUL_TYPES = setOf(
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

private fun deviceLabel(d: AudioDeviceInfo): String {
    val product = d.productName?.toString()?.trim().orEmpty()
    return when (d.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headphones"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY ->
            if (product.isNotBlank()) "USB · $product" else "USB DAC"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET -> product.ifBlank { "Bluetooth" }
        AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing aid"
        AudioDeviceInfo.TYPE_DOCK -> "Dock"
        else -> product.ifBlank { "Output" }
    }
}

private fun deviceIcon(type: Int): ImageVector = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_DOCK -> Icons.Filled.Speaker
    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> Icons.Filled.Usb
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID -> Icons.Filled.Bluetooth
    else -> Icons.Filled.Headphones
}
