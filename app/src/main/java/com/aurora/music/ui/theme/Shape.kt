package com.aurora.music.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aurora.music.data.CornerStyle

/** Corner-radius multiplier for a given [CornerStyle]. PILL is handled separately (very large). */
fun cornerScale(style: Int): Float = when (style) {
    CornerStyle.SHARP -> 0.15f
    CornerStyle.ROUNDED -> 1.6f
    CornerStyle.PILL -> 2.4f
    else -> 1f
}

/** Resolve a base corner radius through the user's corner style. */
fun cornerDp(style: Int, base: Dp): Dp =
    if (style == CornerStyle.PILL) (base.value * 2.4f).dp else (base.value * cornerScale(style)).dp

fun auroraShapes(style: Int): Shapes = Shapes(
    extraSmall = RoundedCornerShape(cornerDp(style, 8.dp)),
    small = RoundedCornerShape(cornerDp(style, 12.dp)),
    medium = RoundedCornerShape(cornerDp(style, 16.dp)),
    large = RoundedCornerShape(cornerDp(style, 24.dp)),
    extraLarge = RoundedCornerShape(cornerDp(style, 32.dp)),
)

val AuroraShapes = auroraShapes(CornerStyle.DEFAULT)
