package com.aurora.music.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

// the website's "needle" ease and hop duration, for controls that move like its own
val PaperNeedle = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
const val PAPER_HOP_MS = 240

/**
 * The website's switch: a square-cornered track, rose when on with an ink block to the right,
 * an ink frame with the block to the left when off.
 */
@Composable
fun PaperSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val knob by animateDpAsState(if (checked) 20.dp else 0.dp, tween(PAPER_HOP_MS, easing = PaperNeedle), label = "paperKnob")
    val track by animateColorAsState(if (checked) PaperTokens.rose else Color.Transparent, tween(PAPER_HOP_MS, easing = PaperNeedle), label = "paperTrack")
    val toggle = if (onCheckedChange != null) Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange) else Modifier
    Box(modifier.then(toggle).padding(vertical = 12.dp).size(44.dp, 24.dp)
        .background(track).border(1.5.dp, if (checked) PaperTokens.rose else PaperTokens.ink)
        .padding(4.dp), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.offset(x = knob).size(16.dp).background(if (enabled) PaperTokens.ink else PaperTokens.ink3))
    }
}

@Composable
@ReadOnlyComposable
fun isPaperTheme(): Boolean = LocalUiPrefs.current.themeStyle == com.aurora.music.data.ThemeStyle.PAPER

// paper has no rounded corners anywhere: components with their own shape route it through here
@Composable
@ReadOnlyComposable
fun paperSquare(shape: Shape): Shape = if (isPaperTheme()) RectangleShape else shape

/** The site's framed box: square, paper-coloured, with a 1px ink rule. Other themes keep [shape] and [fallback]. */
@Composable
fun Modifier.paperFrame(shape: Shape, fallback: @Composable Modifier.() -> Modifier, fill: Color = PaperTokens.paper): Modifier =
    if (isPaperTheme()) this.clip(RectangleShape).background(fill).border(1.dp, PaperTokens.ink) else this.clip(shape).fallback()

/** [clip] that squares the corners in paper, like every edge on the website. */
@Composable
fun Modifier.paperClip(shape: Shape): Modifier = clip(paperSquare(shape))
