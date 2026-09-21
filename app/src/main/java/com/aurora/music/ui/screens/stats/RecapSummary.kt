package com.aurora.music.ui.screens.stats

import com.aurora.music.localization.appPlural

import com.aurora.music.localization.appString
import com.aurora.music.R

import android.graphics.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aurora.music.data.ListeningRecap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RecapStyle { MIDNIGHT, PAPER, AURORA }

fun renderRecap(recap: ListeningRecap, style: RecapStyle): Bitmap {
    val bitmap = Bitmap.createBitmap(1080, 1600, Bitmap.Config.ARGB_8888)
    val c = Canvas(bitmap)
    val dark = style != RecapStyle.PAPER
    c.drawColor(if (dark) Color.rgb(17, 21, 34) else Color.rgb(247, 241, 227))
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val accent = when (style) { RecapStyle.MIDNIGHT -> Color.rgb(145, 177, 255); RecapStyle.PAPER -> Color.rgb(125, 55, 40); RecapStyle.AURORA -> Color.rgb(90, 245, 192) }
    if (style == RecapStyle.AURORA) {
        paint.shader = LinearGradient(0f, 0f, 1080f, 1600f, intArrayOf(Color.rgb(18, 53, 63), Color.rgb(44, 18, 74), Color.rgb(14, 28, 45)), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 1080f, 1600f, paint); paint.shader = null
    }
    fun text(value: String, x: Float, y: Float, size: Float, color: Int = if (dark) Color.WHITE else Color.rgb(30, 28, 25), bold: Boolean = false, width: Float = 900f) {
        paint.color = color; paint.textSize = size; paint.typeface = Typeface.create(if (style == RecapStyle.PAPER) "serif" else "sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        var t = value
        if (paint.measureText(t) > width) { val n = paint.breakText(t, true, width - paint.measureText("…"), null); t = t.take(n) + "…" }
        c.drawText(t, x, y, paint)
    }
    text(appString(R.string.recap_share_title, (recap.window.period.label.uppercase())), 72f, 100f, 27f, accent, true)
    text(recap.window.label, 72f, 190f, 54f, bold = true)
    text("${recap.minutes}", 72f, 325f, 104f, accent, true)
    text(appString(R.string.text_minutes_listened_c66ef9), 76f, 375f, 25f)
    text(appString(R.string.recap_counts, appPlural(R.plurals.play_count, (recap.plays)), appPlural(R.plurals.track_count, (recap.songs.size)), appPlural(R.plurals.active_day_count, (recap.activeDays))), 72f, 440f, 28f)
    fun list(title: String, rows: List<com.aurora.music.data.RecapRank>, y: Float, songs: Boolean = false) {
        text(title, 72f, y, 30f, accent, true)
        rows.take(5).forEachIndexed { i, r ->
            text("${i + 1}", 72f, y + 65 + i * 72, 32f, accent, true)
            text(r.name, 125f, y + 65 + i * 72, 34f, bold = true, width = 655f)
            text(if (songs) r.artist else appPlural(R.plurals.play_count, (r.plays)), 125f, y + 89 + i * 72, 20f, accent, width = 655f)
            text(appString(R.string.text_min_5c8f84, (r.millis / 60_000)), 810f, y + 65 + i * 72, 26f, width = 200f)
        }
    }
    list(appString(R.string.text_top_artists_by_plays_a34fe4), recap.artists, 550f)
    list(appString(R.string.text_top_songs_by_plays_6be796), recap.songs, 1010f, songs = true)
    text(if (recap.estimated) appString(R.string.text_includes_estimated_time_for_older_plays_647a36) else appString(R.string.text_your_music_your_listening_story_2b5397), 72f, 1510f, 24f, accent)
    return bitmap
}

@Composable
fun RecapSummary(recap: ListeningRecap) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var style by remember { mutableStateOf(RecapStyle.MIDNIGHT) }
    val bitmap = remember(recap, style) { renderRecap(recap, style) }
    var status by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<Bitmap?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val image = pending; pending = null
        if (uri != null && image != null) scope.launch {
            status = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } ?: error(appString(R.string.text_no_output_8ffd4d)) }
                    .fold({ appString(R.string.text_picture_saved_32803b) }, { appString(R.string.text_could_not_save_picture_please_try_again_80043a) })
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(appString(R.string.text_your_recap_all_together_933de3), style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RecapStyle.entries.forEach { s -> FilterChip(style == s, { style = s }, label = { Text(when (s) { RecapStyle.MIDNIGHT -> appString(R.string.recap_midnight); RecapStyle.PAPER -> appString(R.string.recap_paper); RecapStyle.AURORA -> "Aurora" }) }) }
        }
        Image(bitmap.asImageBitmap(), appString(R.string.text_recap_picture_preview_with_top_five_artists_and_songs_30bda2), Modifier.fillMaxWidth().aspectRatio(1080f / 1600f))
        Button(onClick = { pending = bitmap; save.launch("Aurora-${recap.window.key.replace(':', '-')}-${style.name.lowercase()}.png") }, modifier = Modifier.fillMaxWidth()) { Text(appString(R.string.text_save_picture_ccdb19)) }
        if (status.isNotEmpty()) Text(status)
    }
}
