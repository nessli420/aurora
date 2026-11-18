package com.aurora.music.ui.components

import androidx.compose.foundation.basicMarquee
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

// one line that scrolls with faded edges only when it overflows; paused stops it while hidden
@Composable
fun MarqueeText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    paused: Boolean = false,
) {
    var fullWidth by remember(text) { mutableIntStateOf(0) }
    var shownWidth by remember { mutableIntStateOf(0) }
    val overflowing = fullWidth > shownWidth && shownWidth > 0
    Text(
        text, style = style, fontWeight = fontWeight,
        maxLines = 1, softWrap = false, color = color,
        onTextLayout = { fullWidth = it.size.width },
        modifier = modifier
            .onSizeChanged { shownWidth = it.width }
            .then(if (overflowing) Modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
                drawContent()
                drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.08f to Color.Black, 0.92f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn)
            } else Modifier)
            .basicMarquee(iterations = if (paused) 0 else Int.MAX_VALUE, initialDelayMillis = 1500, repeatDelayMillis = 2500),
    )
}
