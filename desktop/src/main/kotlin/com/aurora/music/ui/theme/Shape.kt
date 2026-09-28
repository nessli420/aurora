package com.aurora.music.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aurora.music.data.CornerStyle
import com.aurora.music.data.ThemeStyle

fun cornerScale(style: Int): Float = when (style) {
    CornerStyle.SHARP -> 0.15f
    CornerStyle.ROUNDED -> 1.6f
    CornerStyle.PILL -> 2.4f
    else -> 1f
}

fun cornerDp(style: Int, base: Dp): Dp =
    if (style == CornerStyle.PILL) (base.value * 2.4f).dp else (base.value * cornerScale(style)).dp

fun auroraShapes(style: Int, themeStyle: Int = ThemeStyle.AURORA): Shapes = when (themeStyle) {
    ThemeStyle.RETRO -> Shapes(
        extraSmall = RoundedCornerShape(2.dp), small = RoundedCornerShape(3.dp),
        medium = RoundedCornerShape(4.dp), large = RoundedCornerShape(6.dp), extraLarge = RoundedCornerShape(8.dp),
    )
    ThemeStyle.AERO -> Shapes(
        extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(6.dp),
        medium = RoundedCornerShape(8.dp), large = RoundedCornerShape(10.dp), extraLarge = RoundedCornerShape(12.dp),
    )
    ThemeStyle.GLASS -> Shapes(
        extraSmall = RoundedCornerShape(12.dp), small = RoundedCornerShape(18.dp),
        medium = RoundedCornerShape(24.dp), large = RoundedCornerShape(30.dp), extraLarge = RoundedCornerShape(36.dp),
    )
    else -> Shapes(
        extraSmall = RoundedCornerShape(cornerDp(style, 8.dp)),
        small = RoundedCornerShape(cornerDp(style, 12.dp)),
        medium = RoundedCornerShape(cornerDp(style, 16.dp)),
        large = RoundedCornerShape(cornerDp(style, 24.dp)),
        extraLarge = RoundedCornerShape(cornerDp(style, 32.dp)),
    )
}

val AuroraShapes = auroraShapes(CornerStyle.DEFAULT)
