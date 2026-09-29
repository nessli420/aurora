package com.aurora.music.ui.screens.stats

import com.aurora.music.localization.appPlural

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import com.aurora.music.data.ListeningRecap
import com.aurora.music.desktop.ui.FilePickers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.aurora.music.data.label
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface
import org.jetbrains.skia.TextLine

enum class RecapStyle { MIDNIGHT, PAPER, AURORA }

fun renderRecap(recap: ListeningRecap, style: RecapStyle): Image = Surface.makeRasterN32Premul(1080, 1600).use { surface ->
    val c = surface.canvas
    val dark = style != RecapStyle.PAPER
    c.clear(if (dark) Color.makeRGB(17, 21, 34) else Color.makeRGB(247, 241, 227))
    val paint = Paint().apply { isAntiAlias = true }
    val accent = when (style) { RecapStyle.MIDNIGHT -> Color.makeRGB(145, 177, 255); RecapStyle.PAPER -> Color.makeRGB(125, 55, 40); RecapStyle.AURORA -> Color.makeRGB(90, 245, 192) }
    if (style == RecapStyle.AURORA) {
        paint.shader = Shader.makeLinearGradient(0f, 0f, 1080f, 1600f, intArrayOf(Color.makeRGB(18, 53, 63), Color.makeRGB(44, 18, 74), Color.makeRGB(14, 28, 45)), null)
        c.drawRect(Rect.makeWH(1080f, 1600f), paint); paint.shader = null
    }
    val family = if (style == RecapStyle.PAPER) "Georgia" else "Segoe UI"
    val regularFace = FontMgr.default.legacyMakeTypeface(family, FontStyle.NORMAL)
    val boldFace = FontMgr.default.legacyMakeTypeface(family, FontStyle.BOLD)
    fun text(value: String, x: Float, y: Float, size: Float, color: Int = if (dark) Color.WHITE else Color.makeRGB(30, 28, 25), bold: Boolean = false, width: Float = 900f) {
        paint.color = color
        val font = Font(if (bold) boldFace else regularFace, size)
        var line = TextLine.make(value, font)
        if (line.width > width) {
            var n = value.length
            while (n > 0 && TextLine.make(value.take(n) + "…", font).width > width) n--
            line = TextLine.make(value.take(n) + "…", font)
        }
        c.drawTextLine(line, x, y, paint)
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
    surface.makeImageSnapshot()
}

@Composable
fun RecapSummary(recap: ListeningRecap) {
    val scope = rememberCoroutineScope()
    var style by remember { mutableStateOf(RecapStyle.MIDNIGHT) }
    val image = remember(recap, style) { renderRecap(recap, style) }
    val bitmap = remember(image) { image.toComposeImageBitmap() }
    var status by remember { mutableStateOf("") }
    fun save() {
        val file = FilePickers.saveFile(appString(R.string.text_save_picture_ccdb19), "Aurora-${recap.window.key.replace(':', '-')}-${style.name.lowercase()}.png") ?: return
        scope.launch {
            status = withContext(Dispatchers.IO) {
                runCatching { file.writeBytes(requireNotNull(image.encodeToData(EncodedImageFormat.PNG)) { appString(R.string.text_no_output_8ffd4d) }.bytes) }
                    .fold({ appString(R.string.text_picture_saved_32803b) }, { appString(R.string.text_could_not_save_picture_please_try_again_80043a) })
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(appString(R.string.text_your_recap_all_together_933de3), style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RecapStyle.entries.forEach { s -> FilterChip(style == s, { style = s }, label = { Text(when (s) { RecapStyle.MIDNIGHT -> appString(R.string.recap_midnight); RecapStyle.PAPER -> appString(R.string.recap_paper); RecapStyle.AURORA -> "Aurora" }) }) }
        }
        Image(bitmap, appString(R.string.text_recap_picture_preview_with_top_five_artists_and_songs_30bda2), Modifier.widthIn(max = 480.dp).fillMaxWidth().aspectRatio(1080f / 1600f))
        Button(onClick = ::save, modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().pointerHoverIcon(PointerIcon.Hand)) { Text(appString(R.string.text_save_picture_ccdb19)) }
        if (status.isNotEmpty()) Text(status)
    }
}
