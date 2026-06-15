package com.aurora.music.ui.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
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
import com.aurora.music.R
import com.aurora.music.playback.NowPlaying
import com.aurora.music.playback.NowPlayingStore
import com.aurora.music.playback.PlaybackService

class AuroraWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val np = NowPlayingStore.read(context)
        val art = loadArt(context, np.artUri)
        provideContent { WidgetContent(np, art) }
    }

    private suspend fun loadArt(context: Context, uri: String): Bitmap? {
        if (uri.isBlank()) return null
        return runCatching {
            val loader = coil.ImageLoader(context)
            val request = coil.request.ImageRequest.Builder(context)
                .data(uri).allowHardware(false).size(256).build()
            (loader.execute(request).drawable as? BitmapDrawable)?.bitmap
        }.getOrNull()
    }
}

@Composable
private fun WidgetContent(np: NowPlaying, art: Bitmap?) {
    val context = LocalContext.current
    val white = ColorProvider(Color.White)
    val faint = ColorProvider(Color(0xCCFFFFFF))

    fun action(name: String) = actionStartService(
        Intent(context, PlaybackService::class.java).setAction(name),
        isForegroundService = true,
    )

    Row(
        modifier = GlanceModifier.fillMaxSize()
            .background(ColorProvider(Color(0xFF15151A)))
            .cornerRadius(22.dp)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = GlanceModifier.size(56.dp).cornerRadius(12.dp)
                .background(ColorProvider(Color(0xFF26262E))),
            contentAlignment = Alignment.Center,
        ) {
            if (art != null) {
                Image(provider = ImageProvider(art), contentDescription = null, modifier = GlanceModifier.size(56.dp).cornerRadius(12.dp))
            } else {
                Image(
                    provider = ImageProvider(R.drawable.ic_launcher_monochrome),
                    contentDescription = null,
                    colorFilter = androidx.glance.ColorFilter.tint(ColorProvider(Color(0xFFFF2E7E))),
                    modifier = GlanceModifier.size(34.dp),
                )
            }
        }
        Spacer(GlanceModifier.width(12.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                if (np.hasTrack) np.title.ifBlank { "Unknown title" } else "Nothing playing",
                style = TextStyle(color = white, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            if (np.artist.isNotBlank()) {
                Spacer(GlanceModifier.height(2.dp))
                Text(np.artist, style = TextStyle(color = faint, fontSize = 12.sp), maxLines = 1)
            }
        }
        Spacer(GlanceModifier.width(8.dp))
        ControlIcon(android.R.drawable.ic_media_previous, white) { GlanceModifier.clickable(action(PlaybackService.ACTION_PREV)) }
        ControlIcon(
            if (np.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, white,
        ) { GlanceModifier.clickable(action(PlaybackService.ACTION_PLAY_PAUSE)) }
        ControlIcon(android.R.drawable.ic_media_next, white) { GlanceModifier.clickable(action(PlaybackService.ACTION_NEXT)) }
    }
}

@Composable
private fun ControlIcon(resId: Int, tint: ColorProvider, modifierFactory: () -> GlanceModifier) {
    Box(
        modifier = GlanceModifier.size(40.dp).then(modifierFactory()),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(resId),
            contentDescription = null,
            colorFilter = androidx.glance.ColorFilter.tint(tint),
            modifier = GlanceModifier.size(24.dp),
        )
    }
}
