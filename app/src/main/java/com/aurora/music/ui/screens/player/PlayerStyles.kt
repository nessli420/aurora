package com.aurora.music.ui.screens.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import com.aurora.music.data.Lyrics
import com.aurora.music.data.ThemeStyle
import com.aurora.music.localization.appString
import com.aurora.music.model.Song
import com.aurora.music.model.accent
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.Eyebrow
import com.aurora.music.ui.components.LocalPlaybackPosition
import com.aurora.music.ui.components.MarqueeText
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.Motion
import com.aurora.music.ui.theme.paperClip
import com.aurora.music.viewmodel.PlayerUiState
import kotlinx.coroutines.launch

internal enum class StyledView { NOW, LYRICS, QUEUE }

internal class PlayerParts(
    val state: PlayerUiState,
    val view: StyledView,
    val onView: (StyledView) -> Unit,
    val hasQueue: Boolean,
    val wideArt: Boolean,
    val openAlbum: (() -> Unit)?,
    val openArtist: (() -> Unit)?,
    val onCollapse: () -> Unit,
    val onToggleLike: () -> Unit,
    val onSeek: (Float) -> Unit,
    val onOpenSpeedPitch: () -> Unit,
    val onOpenSleep: () -> Unit,
    val onOpenVisualizer: () -> Unit,
    val onOpenOutput: () -> Unit,
    val header: @Composable () -> Unit,
    val videoToggle: (@Composable () -> Unit)?,
    val djControl: (@Composable () -> Unit)?,
    val moreMenu: @Composable () -> Unit,
    val artwork: @Composable (Modifier) -> Unit,
    val chips: @Composable () -> Unit,
    val seek: @Composable (compact: Boolean, accent: Color) -> Unit,
    val transport: @Composable (play: Modifier, fill: Color, ink: Color, framed: Boolean) -> Unit,
    val utilities: @Composable () -> Unit,
    val queue: @Composable (Modifier) -> Unit,
    val queueActions: @Composable () -> Unit,
)

@Composable
internal fun Modifier.flatStage(): Modifier =
    if (LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA) background(MaterialTheme.colorScheme.background) else this

@Composable
internal fun playShape(): Shape = if (LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA) CircleShape else MaterialTheme.shapes.large

@Composable
internal fun PlayerKey(icon: ImageVector, label: String, tint: Color = LocalContentColor.current, onClick: () -> Unit) =
    Icon(icon, label, tint = tint, modifier = Modifier.size(40.dp).paperClip(CircleShape).clickable(onClick = onClick).padding(8.dp))

@Composable
internal fun PlayingFrom(song: Song, modifier: Modifier = Modifier) {
    Column(modifier) {
        Eyebrow(appString(R.string.text_playing_from_5f4dc3), MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        MarqueeText(song.album.ifBlank { appString(R.string.text_aurora_eeee9b) }, MaterialTheme.typography.labelLarge,
            MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun LyricsTranslateKey() {
    val on = LocalUiPrefs.current.lyricsTranslate
    val store = (LocalContext.current.applicationContext as AuroraApplication).container.settingsStore
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val tint by animateColorAsState(if (on) colors.primary else colors.onSurfaceVariant, Motion.state(), label = "translate")
    PlayerKey(Icons.Filled.Translate, appString(R.string.lyrics_translate), tint) { scope.launch { store.setLyricsTranslate(!on) } }
}

@Composable
internal fun NowStrip(
    p: PlayerParts,
    modifier: Modifier = Modifier,
    track: Boolean = true,
    cover: (@Composable () -> Unit)? = null,
    keys: @Composable RowScope.() -> Unit,
) {
    val song = p.state.current
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        if (cover != null) cover() else Artwork(song.artworkUrl, song.accent, Modifier.size(52.dp), corner = 14.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            if (track) SongTitles(song, MaterialTheme.typography.titleMedium, MaterialTheme.typography.bodyMedium, p.openAlbum, p.openArtist)
            else PlayingFrom(song)
        }
        keys()
    }
}

@Composable
internal fun rememberLyrics(song: Song): Lyrics? {
    val repository = (LocalContext.current.applicationContext as AuroraApplication).container.lyricsRepository
    return produceState<Lyrics?>(null, song.id, song.playbackSource?.providerId) {
        value = null
        value = if (song.id.isBlank()) null else repository.lyricsFor(song)
    }.value
}
