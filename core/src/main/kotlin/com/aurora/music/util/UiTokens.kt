package com.aurora.music.util

// plain numbers so the android and desktop ui share one motion and spacing scale
object MotionTokens {
    const val INSTANT = 90
    const val FAST = 150
    const val STANDARD = 220
    const val EMPHASIZED = 320
    const val SLOW = 450

    // cubic bezier control points: x1, y1, x2, y2
    val EASE_OUT = floatArrayOf(0f, 0f, 0.2f, 1f)
    val EASE_IN = floatArrayOf(0.4f, 0f, 1f, 1f)
    val EASE_IN_OUT = floatArrayOf(0.4f, 0f, 0.2f, 1f)
    // front-loaded, for large surfaces that travel far
    val EASE_EMPHASIZED = floatArrayOf(0.05f, 0.7f, 0.1f, 1f)

    const val PRESS_SCALE = 0.96f
    const val PRESS_DIM = 0.08f
    const val ENTER_RISE_DP = 10
}

object SpaceTokens {
    const val XS = 4
    const val S = 8
    const val M = 12
    const val L = 16
    const val GUTTER = 20
    const val XL = 24
    const val SECTION = 32
}
