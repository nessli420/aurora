package com.aurora.music.ui.screens.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.data.ThemeStyle
import com.aurora.music.localization.appString
import com.aurora.music.ui.components.coverTint
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.Motion
import com.aurora.music.ui.theme.PaperTokens
import com.aurora.music.ui.theme.isPaperTheme
import com.aurora.music.ui.theme.paperClip
import com.aurora.music.ui.theme.paperFrame

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpotlightPlayer(p: PlayerParts, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val state = p.state
    val song = state.current
    val now = p.view == StyledView.NOW
    val tint = coverTint(state)
    val page = colors.background
    val strength = LocalUiPrefs.current.playerGradient.coerceIn(0f, 1f)
    // on liquid glass the stage is a wash, so the blurred cover behind the player still shows
    val wash = if (com.aurora.music.ui.theme.isGlassTheme()) 0.55f else 1f
    Column(modifier.drawBehind { drawRect(lerp(page, tint.value, strength), alpha = wash) }.windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerKey(Icons.Filled.KeyboardArrowDown, appString(R.string.text_collapse_9cf188), onClick = p.onCollapse)
            ViewSwitch(p, Modifier.weight(1f))
            p.moreMenu()
        }
        AnimatedContent(
            targetState = p.view,
            transitionSpec = { fadeIn(Motion.enter()) togetherWith fadeOut(Motion.exit()) },
            label = "spotlightView",
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { view ->
            when (view) {
                StyledView.NOW -> Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        p.videoToggle?.invoke()
                        p.djControl?.invoke()
                        PlayingFrom(song, Modifier.weight(1f).padding(horizontal = 8.dp))
                        PlayerCastButton(Modifier.size(40.dp))
                        PlayerKey(Icons.Filled.Speaker, appString(R.string.text_output_device_709178), onClick = p.onOpenOutput)
                    }
                    p.artwork(Modifier.fillMaxWidth().weight(1f).padding(vertical = 12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            SongTitles(song, MaterialTheme.typography.headlineLarge, MaterialTheme.typography.titleMedium, p.openAlbum, p.openArtist, gap = 2.dp)
                            BpmLine(state)
                        }
                        PlayerLike(state, p.onToggleLike, Modifier.size(48.dp).paperClip(CircleShape).background(colors.onSurface.copy(alpha = 0.1f)), 26.dp)
                    }
                    Spacer(Modifier.height(4.dp))
                    p.chips()
                }
                StyledView.LYRICS -> Column(Modifier.fillMaxSize()) {
                    NowStrip(p, Modifier.padding(start = 24.dp, end = 12.dp, top = 8.dp)) { LyricsTranslateKey() }
                    LyricsPane(state, p.onSeek, Modifier.weight(1f).fillMaxWidth(), compact = false, ink = colors.onSurface)
                }
                StyledView.QUEUE -> Column(Modifier.fillMaxSize()) {
                    NowStrip(p, Modifier.padding(start = 24.dp, end = 12.dp, top = 8.dp), track = false) { p.queueActions() }
                    p.queue(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp))
                }
            }
        }
        Column(Modifier.padding(horizontal = 24.dp)) {
            p.seek(!now, colors.onSurface)
            Spacer(Modifier.height(8.dp))
            val play by animateDpAsState(if (now) 76.dp else 60.dp, Motion.state(), label = "spotlightPlay")
            p.transport(Modifier.size(play).clip(playShape()), colors.inverseSurface, colors.inverseOnSurface, true)
            Spacer(Modifier.height(12.dp))
            AnimatedVisibility(now) { p.utilities() }
        }
    }
}

@Composable
private fun ViewSwitch(p: PlayerParts, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val paper = isPaperTheme()
    val shape = if (LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA) CircleShape else MaterialTheme.shapes.small
    val tabs = listOfNotNull(
        (StyledView.QUEUE to R.string.text_queue_d325fc).takeIf { p.hasQueue },
        StyledView.NOW to R.string.text_playing_298c39,
        StyledView.LYRICS to R.string.text_lyrics_8670cb,
    )
    Row(
        modifier.height(48.dp).paperFrame(shape, { background(colors.onSurface.copy(alpha = 0.1f)) }).selectableGroup().padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        tabs.forEach { (view, label) ->
            val selected = p.view == view
            val on by animateFloatAsState(if (selected) 1f else 0f, Motion.state(), label = "viewSwitch")
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(shape)
                    .background((if (paper) colors.primary else colors.inverseSurface).copy(alpha = on))
                    .selectable(selected, role = Role.Tab) { p.onView(view) }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(appString(label), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = if (paper) PaperTokens.ink else lerp(colors.onSurface, colors.inverseOnSurface, on))
            }
        }
    }
}
