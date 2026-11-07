package com.aurora.music.ui.widget

import com.aurora.music.localization.appString

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.media3.common.util.UnstableApi
import androidx.palette.graphics.Palette
import coil.imageLoader
import com.aurora.music.R
import com.aurora.music.playback.NowPlaying
import com.aurora.music.playback.NowPlayingBus
import com.aurora.music.playback.NowPlayingStore
import com.aurora.music.playback.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.glance.color.ColorProvider as DayNight

enum class WidgetLayout { COMPACT, CARD, COVER }
enum class WidgetTheme { DARK, LIGHT, SYSTEM, WALLPAPER, ALBUM }

// per widget, kept in glance's own state for that widget
data class WidgetStyle(
    val layout: WidgetLayout = WidgetLayout.COMPACT,
    val theme: WidgetTheme = WidgetTheme.DARK,
    val opacity: Float = 1f,
) {
    fun writeTo(prefs: MutablePreferences) {
        prefs[LAYOUT] = layout.name
        prefs[THEME] = theme.name
        prefs[OPACITY] = opacity
    }

    companion object {
        private val LAYOUT = stringPreferencesKey("layout")
        private val THEME = stringPreferencesKey("theme")
        private val OPACITY = floatPreferencesKey("opacity")

        fun of(prefs: Preferences) = WidgetStyle(
            WidgetLayout.entries.firstOrNull { it.name == prefs[LAYOUT] } ?: WidgetLayout.COMPACT,
            WidgetTheme.entries.firstOrNull { it.name == prefs[THEME] } ?: WidgetTheme.DARK,
            (prefs[OPACITY] ?: 1f).coerceIn(0f, 1f),
        )
    }
}

private class Art(val uri: String, val bitmap: Bitmap?, val color: Int?)

class AuroraWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // a fresh process starts with an empty bus; the saved snapshot stands in until the service publishes
        NowPlayingBus.state.compareAndSet(NowPlaying(), NowPlayingStore.read(context))
        val first = loadArt(context, NowPlayingBus.state.value.artUri)
        provideContent {
            val np by NowPlayingBus.state.collectAsState()
            val art by produceState(first, np.artUri) { if (value.uri != np.artUri) value = loadArt(context, np.artUri) }
            WidgetContent(np, art, WidgetStyle.of(currentState()))
        }
    }

    private suspend fun loadArt(context: Context, uri: String): Art {
        if (uri.isBlank()) return Art(uri, null, null)
        val bitmap = runCatching {
            val request = coil.request.ImageRequest.Builder(context)
                .data(uri).allowHardware(false).size(512).build()
            (context.imageLoader.execute(request).drawable as? BitmapDrawable)?.bitmap
        }.getOrNull()
        val swatch = bitmap?.let { withContext(Dispatchers.Default) { runCatching { Palette.from(it).generate() }.getOrNull() } }
            ?.let { it.darkMutedSwatch ?: it.darkVibrantSwatch ?: it.dominantSwatch }
        return Art(uri, bitmap, swatch?.rgb)
    }
}

private class WidgetColors(val background: ColorProvider, val content: ColorProvider, val faint: ColorProvider)

private val DARK = Color(0xFF15151A) to Color.White
private val LIGHT = Color(0xFFF4F4F7) to Color(0xFF15151A)

private fun widgetColors(context: Context, theme: WidgetTheme, opacity: Float, album: Int?): WidgetColors {
    val (day, night) = when (theme) {
        WidgetTheme.DARK -> DARK to DARK
        WidgetTheme.LIGHT -> LIGHT to LIGHT
        WidgetTheme.WALLPAPER -> if (Build.VERSION.SDK_INT >= 31) {
            fun c(id: Int) = Color(context.getColor(id))
            (c(android.R.color.system_accent2_50) to c(android.R.color.system_accent1_900)) to
                (c(android.R.color.system_accent2_800) to c(android.R.color.system_accent1_50))
        } else LIGHT to DARK
        WidgetTheme.ALBUM -> album?.let(::Color)?.let { bg -> (bg to if (bg.luminance() > .5f) DARK.first else Color.White).let { it to it } }
            ?: (LIGHT to DARK)
        WidgetTheme.SYSTEM -> LIGHT to DARK
    }
    return WidgetColors(
        DayNight(day = day.first.copy(alpha = opacity), night = night.first.copy(alpha = opacity)),
        DayNight(day = day.second, night = night.second),
        DayNight(day = day.second.copy(alpha = .75f), night = night.second.copy(alpha = .75f)),
    )
}

@Composable
private fun WidgetContent(np: NowPlaying, art: Art, style: WidgetStyle) {
    val colors = widgetColors(LocalContext.current, style.theme, style.opacity, art.color)
    when (style.layout) {
        WidgetLayout.COMPACT -> Row(
            GlanceModifier.fillMaxSize().background(colors.background).cornerRadius(22.dp).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(art.bitmap, 56.dp, 12.dp)
            Spacer(GlanceModifier.width(12.dp))
            Titles(np, colors, GlanceModifier.defaultWeight())
            Spacer(GlanceModifier.width(8.dp))
            Controls(np, colors.content)
        }
        WidgetLayout.CARD -> {
            val size = LocalSize.current
            Row(
                GlanceModifier.fillMaxSize().background(colors.background).cornerRadius(22.dp).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // leave the text column room for the three 40dp controls
                Artwork(art.bitmap, minOf(size.height - 24.dp, size.width - 158.dp).coerceAtLeast(48.dp), 16.dp)
                Spacer(GlanceModifier.width(14.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Titles(np, colors, GlanceModifier.fillMaxWidth(), 17.sp, 2)
                    Spacer(GlanceModifier.height(10.dp))
                    Controls(np, colors.content)
                }
            }
        }
        WidgetLayout.COVER -> Box(GlanceModifier.fillMaxSize().cornerRadius(22.dp)) {
            if (art.bitmap != null) {
                Image(ImageProvider(art.bitmap), null, GlanceModifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Box(GlanceModifier.fillMaxSize().background(colors.background), contentAlignment = Alignment.Center) { Logo(48.dp) }
            }
            Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.Bottom) {
                Row(
                    GlanceModifier.fillMaxWidth().background(colors.background).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Titles(np, colors, GlanceModifier.defaultWeight())
                    Controls(np, colors.content)
                }
            }
        }
    }
}

@Composable
private fun Artwork(art: Bitmap?, side: Dp, corner: Dp) {
    Box(
        GlanceModifier.size(side).cornerRadius(corner).background(ColorProvider(Color(0x33808080))),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) Image(ImageProvider(art), null, GlanceModifier.size(side).cornerRadius(corner), contentScale = ContentScale.Crop)
        else Logo(side * .6f)
    }
}

@Composable
private fun Logo(side: Dp) {
    Image(
        provider = ImageProvider(R.drawable.ic_aurora_logo),
        contentDescription = null,
        colorFilter = androidx.glance.ColorFilter.tint(ColorProvider(Color(0xFFFF2E7E))),
        modifier = GlanceModifier.size(side),
    )
}

@Composable
private fun Titles(np: NowPlaying, colors: WidgetColors, modifier: GlanceModifier, titleSize: TextUnit = 15.sp, titleLines: Int = 1) {
    Column(modifier) {
        Text(
            if (np.hasTrack) np.title.ifBlank { appString(R.string.text_unknown_title_bceca3) } else appString(R.string.text_nothing_playing_13ae37),
            style = TextStyle(color = colors.content, fontSize = titleSize, fontWeight = FontWeight.Bold),
            maxLines = titleLines,
        )
        if (np.artist.isNotBlank()) {
            Spacer(GlanceModifier.height(2.dp))
            Text(np.artist, style = TextStyle(color = colors.faint, fontSize = 12.sp), maxLines = 1)
        }
    }
}

@Composable
@OptIn(UnstableApi::class)
private fun Controls(np: NowPlaying, tint: ColorProvider) {
    val context = LocalContext.current
    fun action(name: String) = actionStartService(
        Intent(context, PlaybackService::class.java).setAction(name),
        isForegroundService = true,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        ControlIcon(android.R.drawable.ic_media_previous, appString(R.string.text_previous_50f942), tint) {
            GlanceModifier.clickable(action(PlaybackService.ACTION_PREV))
        }
        ControlIcon(
            if (np.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            appString(if (np.isPlaying) R.string.text_pause_781961 else R.string.text_play_5d12bd), tint,
        ) { GlanceModifier.clickable(action(PlaybackService.ACTION_PLAY_PAUSE)) }
        ControlIcon(android.R.drawable.ic_media_next, appString(R.string.text_next_bc9819), tint) {
            GlanceModifier.clickable(action(PlaybackService.ACTION_NEXT))
        }
    }
}

@Composable
private fun ControlIcon(resId: Int, label: String, tint: ColorProvider, modifierFactory: () -> GlanceModifier) {
    Box(
        modifier = GlanceModifier.size(40.dp).then(modifierFactory()),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(resId),
            contentDescription = label,
            colorFilter = androidx.glance.ColorFilter.tint(tint),
            modifier = GlanceModifier.size(24.dp),
        )
    }
}
