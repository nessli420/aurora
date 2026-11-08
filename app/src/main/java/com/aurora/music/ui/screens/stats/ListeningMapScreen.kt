package com.aurora.music.ui.screens.stats

import android.graphics.drawable.GradientDrawable
import com.aurora.music.ui.theme.paperClip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.aurora.music.ui.theme.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import com.aurora.music.data.DetailedPlay
import com.aurora.music.data.RecapPeriod
import com.aurora.music.data.RecapWindow
import com.aurora.music.localization.appString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import kotlin.math.roundToLong

// plays within roughly 30 m share one dot so a long session at home stays readable
private data class Spot(val key: Pair<Long, Long>, val point: GeoPoint, val plays: List<DetailedPlay>)

private fun spots(plays: List<DetailedPlay>): List<Spot> = plays.groupBy {
    (it.context.latitude!! / CELL).roundToLong() to (it.context.longitude!! / CELL).roundToLong()
}.map { (key, list) ->
    Spot(key, GeoPoint(list.map { it.context.latitude!! }.average(), list.map { it.context.longitude!! }.average()), list.sortedByDescending { it.startedAt })
}

private const val CELL = 0.0003

private class MapMemo {
    var framed: List<Spot>? = null
    var focused: Pair<Long, Long>? = null
}

@Composable
fun ListeningMapScreen(contentPadding: PaddingValues, onBack: () -> Unit, songId: String? = null, at: Long? = null) {
    val context = LocalContext.current
    val container = (context.applicationContext as AuroraApplication).container
    val scope = rememberCoroutineScope()
    var window by remember { mutableStateOf(RecapWindow.containing(RecapPeriod.MONTH, LocalDate.now())) }
    var refresh by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val zone = remember { ZoneId.systemDefault() }
    val plays by produceState<List<DetailedPlay>?>(null, window, songId, refresh) {
        value = withContext(Dispatchers.IO) {
            if (songId != null) container.listeningDetails.located(songId = songId)
            else container.listeningDetails.located(window.start.atStartOfDay(zone).toInstant().toEpochMilli(),
                window.end.atStartOfDay(zone).toInstant().toEpochMilli())
        }
    }
    val spots = remember(plays) { spots(plays.orEmpty()) }
    val highlighted = remember(plays, at) { at?.let { t -> plays.orEmpty().minByOrNull { kotlin.math.abs(it.startedAt - t) } } }
    val focus = selected?.let { key -> spots.firstOrNull { it.key == key } }
        ?: highlighted?.let { h -> spots.firstOrNull { s -> s.plays.any { it.id == h.id } } }
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(top = topInset + 6.dp, start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, appString(R.string.text_back_b52b36),
                modifier = Modifier.size(40.dp).paperClip(CircleShape).clickable(onClick = onBack).padding(8.dp))
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(highlighted?.track?.title ?: appString(R.string.map_title), style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (songId != null) Text(appString(R.string.map_song_subtitle, plays.orEmpty().size), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Filled.DeleteForever, appString(R.string.insights_delete_locations), tint = MaterialTheme.colorScheme.error)
            }
        }
        if (songId == null) PeriodPicker(window) { window = it; selected = null }
        // the map view paints whole tiles past its edges, so it has to be clipped
        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp).paperClip(RoundedCornerShape(20.dp))) {
            OsmMap(spots, focus?.key, highlighted?.id) { selected = it }
            if (plays != null && spots.isEmpty()) Text(appString(R.string.map_empty),
                Modifier.align(Alignment.Center).paperClip(CircleShape).background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium)
        }
        val listed = focus?.plays ?: if (songId != null) plays.orEmpty().sortedByDescending { it.startedAt } else emptyList()
        Spacer(Modifier.height(4.dp))
        if (listed.isEmpty() && spots.isNotEmpty()) Text(appString(R.string.map_tap_hint), Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (listed.isNotEmpty()) {
            Text(appString(R.string.map_plays_here, listed.size), Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp),
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp), contentPadding = PaddingValues(bottom = 4.dp)) {
                items(listed, key = { it.id }) { play ->
                    Row(Modifier.fillMaxWidth().clickable {
                        selected = spots.firstOrNull { s -> s.plays.any { it.id == play.id } }?.key
                    }.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(play.track.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (play.id == highlighted?.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            Text(play.track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(play.startedAt)),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(contentPadding.calculateBottomPadding()))
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(appString(R.string.insights_delete_locations)) },
        text = { Text(appString(R.string.insights_delete_locations_body)) },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                scope.launch {
                    container.listeningDetails.clearLocations()
                    selected = null
                    refresh++
                }
            }) { Text(appString(R.string.insights_delete_confirm), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(appString(R.string.text_cancel_77dfd2)) } },
    )
}

@Composable
private fun OsmMap(spots: List<Spot>, focus: Pair<Long, Long>?, highlight: String?, onSelect: (Pair<Long, Long>) -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    val ring = MaterialTheme.colorScheme.surface.toArgb()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val map = remember {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 3.0
            controller.setZoom(4.0)
        }
    }
    DisposableEffect(lifecycle, map) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) map.onResume()
            if (event == Lifecycle.Event.ON_PAUSE) map.onPause()
        }
        lifecycle.addObserver(observer)
        map.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            map.onPause()
            map.onDetach()
        }
    }
    val memo = remember { MapMemo() }
    AndroidView({ map }, Modifier.fillMaxSize()) { view ->
        view.overlays.clear()
        view.overlays.add(CopyrightOverlay(context))
        spots.forEach { spot ->
            val active = spot.key == focus || spot.plays.any { it.id == highlight }
            val size = with(density) { (if (active) 26 else 18).dp.roundToPx() }
            view.overlays.add(Marker(view).apply {
                position = spot.point
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (active) accent else (accent and 0x00FFFFFF) or (0xB0 shl 24))
                    setStroke(with(density) { 2.dp.roundToPx() }, ring)
                    setSize(size, size)
                }
                setOnMarkerClickListener { _, _ -> onSelect(spot.key); true }
            })
        }
        val target = spots.firstOrNull { it.key == focus }
        when {
            target != null && memo.focused != focus -> view.post { view.controller.animateTo(target.point, maxOf(view.zoomLevelDouble, 16.0), 400L) }
            memo.framed != spots && spots.size == 1 -> view.controller.setCenter(spots.first().point).also { view.controller.setZoom(16.0) }
            memo.framed != spots && spots.size > 1 -> view.post {
                view.zoomToBoundingBox(BoundingBox.fromGeoPointsSafe(spots.map { it.point }).increaseByScale(1.3f), false)
            }
        }
        memo.focused = focus
        memo.framed = spots
        view.invalidate()
    }
}
