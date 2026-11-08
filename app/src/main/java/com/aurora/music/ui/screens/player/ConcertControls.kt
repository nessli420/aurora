package com.aurora.music.ui.screens.player

import androidx.compose.foundation.Canvas
import com.aurora.music.ui.theme.paperClip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.aurora.music.R
import com.aurora.music.data.ConcertEarplugs
import com.aurora.music.data.ConcertLayout
import com.aurora.music.data.ConcertPrefs
import com.aurora.music.data.ConcertScene
import com.aurora.music.data.ConcertSeat
import com.aurora.music.data.ConcertSound
import com.aurora.music.data.ConcertSubs
import com.aurora.music.data.ConcertVenue
import com.aurora.music.localization.appString
import com.aurora.music.playback.engine.ConcertAcoustics
import com.aurora.music.ui.screens.settings.SegmentedChoice
import java.text.NumberFormat
import kotlin.math.atan2
import kotlin.math.hypot

internal fun concertVenueName(id: Int): String = appString(when (id) {
    ConcertVenue.CLUB -> R.string.concert_venue_club
    ConcertVenue.THEATRE -> R.string.concert_venue_theatre
    ConcertVenue.STADIUM -> R.string.concert_venue_stadium
    ConcertVenue.FESTIVAL -> R.string.concert_venue_festival
    ConcertVenue.AMPHITHEATRE -> R.string.concert_venue_amphitheatre
    ConcertVenue.BASEMENT -> R.string.concert_venue_basement
    ConcertVenue.CHURCH -> R.string.concert_venue_church
    ConcertVenue.WAREHOUSE -> R.string.concert_venue_warehouse
    else -> R.string.concert_venue_arena
})

internal fun concertSoundName(id: Int): String = appString(when (id) {
    ConcertSound.ROCK -> R.string.concert_sound_rock
    ConcertSound.METAL -> R.string.concert_sound_metal
    ConcertSound.SHOEGAZE -> R.string.concert_sound_shoegaze
    ConcertSound.JAZZ -> R.string.concert_sound_jazz
    ConcertSound.HIP_HOP -> R.string.concert_sound_hip_hop
    ConcertSound.ELECTRONIC -> R.string.concert_sound_electronic
    ConcertSound.CLASSICAL -> R.string.concert_sound_classical
    ConcertSound.INDIE -> R.string.concert_sound_indie
    ConcertSound.PUNK -> R.string.concert_sound_punk
    ConcertSound.POST_PUNK -> R.string.concert_sound_post_punk
    ConcertSound.ACOUSTIC -> R.string.concert_sound_acoustic
    else -> R.string.concert_sound_pop
})

private fun concertSeatName(venue: Int, id: Int): String = appString(when (id) {
    ConcertSeat.FRONT -> R.string.concert_seat_front
    ConcertSeat.BACK -> R.string.concert_seat_back
    ConcertSeat.SIDE -> R.string.concert_seat_side
    ConcertSeat.STANDS -> if (venue == ConcertVenue.THEATRE) R.string.concert_seat_balcony else R.string.concert_seat_stands
    ConcertSeat.UPPER -> R.string.concert_seat_upper
    else -> R.string.concert_seat_middle
})

private val SCENE_NAMES = intArrayOf(
    R.string.concert_scene_0, R.string.concert_scene_1, R.string.concert_scene_2, R.string.concert_scene_3, R.string.concert_scene_4,
    R.string.concert_scene_5, R.string.concert_scene_6, R.string.concert_scene_7, R.string.concert_scene_8, R.string.concert_scene_9,
    R.string.concert_scene_10, R.string.concert_scene_11, R.string.concert_scene_12,
)

// keeps the picked chip in view, so a long row never hides what is selected
@Composable
internal fun ChoiceChips(labels: List<String>, selected: Int, modifier: Modifier = Modifier, onPick: (Int) -> Unit) {
    val scroll = rememberScrollState()
    val starts = remember { mutableStateMapOf<Int, Int>() }
    val inset = with(LocalDensity.current) { 32.dp.roundToPx() }
    val target = starts[selected]
    LaunchedEffect(target) { if (target != null) scroll.animateScrollTo((target - inset).coerceAtLeast(0)) }
    Row(modifier.fillMaxWidth().horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { id, label ->
            val active = id == selected
            Text(
                label,
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                color = if (active) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.onGloballyPositioned { starts[id] = it.positionInParent().x.toInt() }.paperClip(RoundedCornerShape(50))
                    .background(if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable { onPick(id) }.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
internal fun ReverbModeChips(concert: Boolean, modifier: Modifier = Modifier, onPick: (Boolean) -> Unit) =
    SegmentedChoice(listOf(appString(R.string.reverb_mode_studio), appString(R.string.reverb_mode_concert)), if (concert) 1 else 0, modifier) { onPick(it == 1) }

@Composable
internal fun ConcertSceneChips(concert: ConcertPrefs, modifier: Modifier = Modifier, label: Boolean = true, onChange: (ConcertPrefs) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label) ChipLabel(appString(R.string.concert_section_scenes))
        ChoiceChips(SCENE_NAMES.map { appString(it) }, ConcertScene.ALL.indexOfFirst { it.matches(concert) }) { onChange(ConcertScene.ALL[it].applyTo(concert)) }
    }
}

@Composable
internal fun ConcertStyleChips(concert: ConcertPrefs, modifier: Modifier = Modifier, labelVenue: Boolean = true, onChange: (ConcertPrefs) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (labelVenue) ChipLabel(appString(R.string.concert_section_venue))
        ChoiceChips(ConcertVenue.ORDER.map(::concertVenueName), ConcertVenue.ORDER.indexOf(concert.venue)) { onChange(concert.withVenue(ConcertVenue.ORDER[it])) }
        ChipLabel(appString(R.string.concert_sound))
        ChoiceChips(ConcertSound.ORDER.map(::concertSoundName), ConcertSound.ORDER.indexOf(concert.sound)) { onChange(concert.withSound(ConcertSound.ORDER[it])) }
    }
}

@Composable
private fun ChipLabel(text: String) =
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))

@Composable
internal fun ConcertRoomSliders(concert: ConcertPrefs, modifier: Modifier = Modifier, onChange: (ConcertPrefs) -> Unit) {
    val venue = ConcertVenue.ALL[concert.venue]
    val percent = remember { NumberFormat.getPercentInstance() }
    Column(modifier) {
        ChipLabel(appString(R.string.concert_stage))
        SegmentedChoice(listOf(appString(R.string.concert_stage_end), appString(R.string.concert_stage_centre)), concert.layout(), Modifier.padding(vertical = 8.dp)) {
            onChange(concert.withLayout(if (it == 1) ConcertLayout.CENTRE else ConcertLayout.END))
        }
        ConcertSlider(appString(R.string.concert_size), concert.size,
            label = { appString(R.string.concert_size_value, "%.0f".format(venue.widthM(it)), "%.0f".format(venue.lengthM(it))) }) { onChange(concert.copy(size = it)) }
        ConcertSlider(appString(R.string.concert_openness), concert.openness, label = {
            when { it < 0.03f -> appString(R.string.concert_enclosed); it > 0.97f -> appString(R.string.concert_open_air); else -> percent.format(it) }
        }) { onChange(concert.copy(openness = it)) }
        val bands = remember(concert) { ConcertAcoustics.room(concert).decayBands }
        Text(appString(R.string.concert_decay) + "  " + listOf(0 to "125", 2 to "500", 4 to "2k", 6 to "8k").joinToString("  ·  ") { (b, hz) ->
            (if (hz.endsWith("k")) appString(R.string.reverb_value_khz, hz.dropLast(1)) else appString(R.string.reverb_value_hz, hz)) + " " +
                appString(R.string.reverb_value_seconds, "%.1f".format(bands[b]))
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
    }
}

// how loud the show runs, and in the settings screen how busy it is and what's in your ears
@Composable
internal fun ConcertShowControls(concert: ConcertPrefs, modifier: Modifier = Modifier, full: Boolean = false, onChange: (ConcertPrefs) -> Unit) {
    val percent = remember { NumberFormat.getPercentInstance() }
    Column(modifier) {
        ConcertSlider(appString(R.string.concert_loudness), concert.showDb, ConcertPrefs.MIN_DB..ConcertPrefs.MAX_DB,
            label = { appString(R.string.concert_loudness_value, "%.0f".format(it)) }) { onChange(concert.copy(showDb = it)) }
        if (full) {
            ConcertSlider(appString(R.string.concert_crowd), concert.crowd, label = percent::format) { onChange(concert.copy(crowd = it)) }
            ConcertSlider(appString(R.string.concert_audience), concert.occupancy, label = {
                when { it < 0.03f -> appString(R.string.concert_audience_empty); it > 0.97f -> appString(R.string.concert_audience_packed); else -> percent.format(it) }
            }) { onChange(concert.copy(occupancy = it)) }
            ConcertSlider(appString(R.string.concert_stage_sound), concert.stageDb, -12f..12f,
                label = { appString(R.string.concert_stage_sound_value, (if (it > 0.5f) "+" else "") + "%.0f".format(it)) }) { onChange(concert.copy(stageDb = it)) }
            ChipLabel(appString(R.string.concert_subs))
            SegmentedChoice(listOf(appString(R.string.concert_subs_auto), appString(R.string.concert_subs_split), appString(R.string.concert_subs_centre),
                appString(R.string.concert_subs_cardioid)), concert.subLayout, Modifier.padding(vertical = 8.dp)) {
                onChange(concert.copy(subLayout = it.coerceIn(ConcertSubs.AUTO, ConcertSubs.CARDIOID)))
            }
            ChipLabel(appString(R.string.concert_earplugs))
            SegmentedChoice(listOf(appString(R.string.concert_earplugs_none), appString(R.string.concert_earplugs_musician), appString(R.string.concert_earplugs_foam)),
                concert.earplugs, Modifier.padding(vertical = 8.dp)) { onChange(concert.copy(earplugs = it.coerceIn(ConcertEarplugs.NONE, ConcertEarplugs.FOAM))) }
            ChipLabel(appString(R.string.concert_listen))
            SegmentedChoice(listOf(appString(R.string.concert_listen_headphones), appString(R.string.concert_listen_speakers)),
                if (concert.binaural) 0 else 1, Modifier.padding(vertical = 8.dp)) { onChange(concert.copy(binaural = it == 0)) }
        }
    }
}

// the weather out there: humidity and temperature change how much treble the air takes over distance
@Composable
internal fun ConcertAirControls(concert: ConcertPrefs, modifier: Modifier = Modifier, onChange: (ConcertPrefs) -> Unit) {
    val percent = remember { NumberFormat.getPercentInstance() }
    Column(modifier) {
        ConcertSlider(appString(R.string.concert_temperature), concert.temperatureC, -10f..40f,
            label = { appString(R.string.concert_temperature_value, "%.0f".format(it)) }) { onChange(concert.copy(temperatureC = it)) }
        ConcertSlider(appString(R.string.concert_humidity), concert.humidity, 5f..100f, label = { percent.format(it / 100f) }) { onChange(concert.copy(humidity = it)) }
    }
}

// a measured room from the impulse library replaces the modelled reflections and tail
@Composable
internal fun ConcertMeasuredRoom(concert: ConcertPrefs, rooms: List<Pair<String, String>>, modifier: Modifier = Modifier, onChange: (ConcertPrefs) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoiceChips(listOf(appString(R.string.concert_room_modelled)) + rooms.map { it.second },
            rooms.indexOfFirst { it.first == concert.roomImpulse } + 1) { onChange(concert.copy(roomImpulse = rooms.getOrNull(it - 1)?.first)) }
        if (rooms.isEmpty()) Text(appString(R.string.concert_room_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ConcertPositionPicker(concert: ConcertPrefs, modifier: Modifier = Modifier, hint: Boolean = false, onChange: (ConcertPrefs) -> Unit) {
    var draft by remember { mutableStateOf<ConcertPrefs?>(null) }
    val shown = draft ?: concert
    val room = remember(shown) { ConcertAcoustics.room(shown) }
    val seats = ConcertSeat.available(shown.venue)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ConcertVenueMap(shown, concert, room.listenerHeightM > 0,
            appString(if (room.listenerHeightM > 0) R.string.concert_distance_raised else R.string.concert_distance, "%.0f".format(room.distanceM)),
            onDraft = { draft = it }, onChange = onChange)
        ChoiceChips(seats.map { concertSeatName(shown.venue, it) }, seats.indexOf(shown.seat())) { onChange(concert.withSeat(seats[it])) }
        if (hint) Text(appString(R.string.concert_map_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// top-down floor plan sized to the venue itself, so only touches on the floor move the dot (or the stage); stands get tier lines
@Composable
private fun ConcertVenueMap(shown: ConcertPrefs, stored: ConcertPrefs, raised: Boolean, distance: String, onDraft: (ConcertPrefs?) -> Unit, onChange: (ConcertPrefs) -> Unit) {
    val venue = ConcertVenue.ALL[shown.venue]
    val aspect = (venue.widthM(shown.size) / venue.lengthM(shown.size)).coerceIn(0.55f, 1.8f)
    val latest by rememberUpdatedState(shown)
    val base by rememberUpdatedState(stored)
    val draft by rememberUpdatedState(onDraft)
    val change by rememberUpdatedState(onChange)
    val floor = MaterialTheme.colorScheme.surfaceContainerHighest
    val tiers = MaterialTheme.colorScheme.onSurface
    val wall = MaterialTheme.colorScheme.outline
    val stageColor = MaterialTheme.colorScheme.secondary
    val you = MaterialTheme.colorScheme.tertiary
    val youRing = MaterialTheme.colorScheme.onTertiary
    val label = appString(R.string.concert_map_label)
    fun place(at: Offset, canvas: Size, pad: Float, stage: Boolean, from: ConcertPrefs): ConcertPrefs {
        val r = venueRect(canvas, aspect, pad)
        val x = ((at.x - r.left) / r.width).coerceIn(0.02f, 0.98f)
        val y = ((at.y - r.top) / r.height).coerceIn(0.02f, 0.98f)
        return if (stage) from.copy(stageX = x, stageY = y) else from.copy(listenerX = x, listenerY = y)
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.heightIn(max = 200.dp).aspectRatio(aspect).semantics { contentDescription = label }
                .pointerInput(aspect) {
                    detectTapGestures { at -> change(place(at, size.toSize(), 2.dp.toPx(), stage = false, from = base)) }
                }
                .pointerInput(aspect) {
                    var stage = false
                    detectDragGestures(
                        onDragStart = { at ->
                            val r = venueRect(size.toSize(), aspect, 2.dp.toPx())
                            stage = (at - Offset(r.left + base.stageX * r.width, r.top + base.stageY * r.height)).getDistance() < 28.dp.toPx()
                        },
                        onDrag = { pointer, _ ->
                            pointer.consume()
                            val next = place(pointer.position, size.toSize(), 2.dp.toPx(), stage, latest)
                            draft(next); change(next)
                        },
                        onDragEnd = { draft(null) },
                        onDragCancel = { draft(null) },
                    )
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val r = venueRect(size, aspect, 2.dp.toPx())
                val corner = CornerRadius(14.dp.toPx())
                drawRoundRect(floor, r.topLeft, r.size, corner)
                val f = venue.floor(shown.size)
                if (venue.standSides != 0) clipRect(r.left, r.top, r.right, r.bottom) {
                    val pit = Rect(r.left + f[0] * r.width, r.top + f[1] * r.height, r.left + f[2] * r.width, r.top + f[3] * r.height)
                    drawRoundRect(tiers.copy(alpha = 0.07f), r.topLeft, r.size, corner)
                    drawRect(floor, pit.topLeft, pit.size)
                    drawRect(stageColor.copy(alpha = 0.10f), pit.topLeft, pit.size)
                    val line = tiers.copy(alpha = 0.16f); val w = 1.dp.toPx()
                    for (k in 1..2) {
                        val t = k / 3f
                        if (venue.standSides and ConcertVenue.FRONT != 0) drawLine(line, Offset(r.left, r.top + (pit.top - r.top) * t), Offset(r.right, r.top + (pit.top - r.top) * t), w)
                        if (venue.standSides and ConcertVenue.BACK != 0) drawLine(line, Offset(r.left, r.bottom - (r.bottom - pit.bottom) * t), Offset(r.right, r.bottom - (r.bottom - pit.bottom) * t), w)
                        if (venue.standSides and ConcertVenue.LEFT != 0) drawLine(line, Offset(r.left + (pit.left - r.left) * t, r.top), Offset(r.left + (pit.left - r.left) * t, r.bottom), w)
                        if (venue.standSides and ConcertVenue.RIGHT != 0) drawLine(line, Offset(r.right - (r.right - pit.right) * t, r.top), Offset(r.right - (r.right - pit.right) * t, r.bottom), w)
                    }
                }
                drawRoundRect(wall.copy(alpha = 1f - 0.7f * shown.openness), r.topLeft, r.size, corner,
                    style = Stroke(2.dp.toPx(), pathEffect = if (shown.openness > 0.5f) PathEffect.dashPathEffect(floatArrayOf(12f, 10f)) else null))
                val s = Offset(r.left + shown.stageX * r.width, r.top + shown.stageY * r.height)
                val p = Offset(r.left + shown.listenerX * r.width, r.top + shown.listenerY * r.height)
                clipRect(r.left, r.top, r.right, r.bottom) {
                    for (i in 1..4) drawCircle(stageColor.copy(alpha = 0.12f), radius = i * r.minDimension * 0.2f, center = s, style = Stroke(1.5.dp.toPx()))
                }
                drawLine(you.copy(alpha = 0.6f), p, s, strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
                val centred = shown.layout() == ConcertLayout.CENTRE
                val centre = r.center - s
                val facing = if (!centred && hypot(centre.x / r.width, centre.y / r.height) > 0.05f) centre else p - s
                val stageW = (venue.stageWidthM(shown.size) / venue.widthM(shown.size) * r.width).coerceAtLeast(20.dp.toPx())
                val stageD = if (centred) stageW * 0.6f else 9.dp.toPx()
                rotate(Math.toDegrees(atan2(facing.y, facing.x).toDouble()).toFloat() - 90f, pivot = s) {
                    drawRoundRect(stageColor, Offset(s.x - stageW / 2, s.y - stageD / 2), Size(stageW, stageD), CornerRadius(4.dp.toPx()))
                }
                if (raised) drawCircle(you.copy(alpha = 0.3f), 15.dp.toPx(), p)
                drawCircle(you, 9.dp.toPx(), p)
                drawCircle(youRing, 9.dp.toPx(), p, style = Stroke(2.dp.toPx()))
            }
            Text(distance, Modifier.align(Alignment.BottomEnd).padding(8.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun venueRect(canvas: Size, aspect: Float, pad: Float): Rect {
    val w = canvas.width - 2 * pad; val h = canvas.height - 2 * pad
    val size = if (w / h > aspect) Size(h * aspect, h) else Size(w, w / aspect)
    return Rect(Offset((canvas.width - size.width) / 2, (canvas.height - size.height) / 2), size)
}

// drafts the thumb locally so datastore round-trips never make it jump
@Composable
private fun ConcertSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float> = 0f..1f, label: (Float) -> String, onChange: (Float) -> Unit) {
    var draft by remember { mutableStateOf<Float?>(null) }
    val shown = draft ?: value
    Column {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(label(shown), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = shown.coerceIn(range.start, range.endInclusive),
            onValueChange = { draft = it; onChange(it) },
            onValueChangeFinished = { draft = null },
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.secondary, activeTrackColor = MaterialTheme.colorScheme.secondary),
        )
    }
}
