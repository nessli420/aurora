package com.aurora.music.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.aurora.music.desktop.natives.WindowNative

@Composable
fun WindowChrome(windowHandle: Long) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    val caption = colors.background.toArgb()
    val text = colors.onBackground.toArgb()
    val border = colors.outline.toArgb()
    LaunchedEffect(windowHandle, dark, caption, text, border) {
        if (windowHandle == 0L) return@LaunchedEffect
        WindowNative.setDarkMode(windowHandle, dark)
        WindowNative.setCaptionColor(windowHandle, caption)
        WindowNative.setTextColor(windowHandle, text)
        WindowNative.setBorderColor(windowHandle, border)
    }
}
