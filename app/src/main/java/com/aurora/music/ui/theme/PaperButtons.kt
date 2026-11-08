package com.aurora.music.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

// Same-name stand-ins for the material buttons. Outside paper they pass straight through;
// in paper they become the website's buttons: square, framed in ink, rose for the primary one.

private val inkRule get() = BorderStroke(1.dp, PaperTokens.ink)

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors? = null,
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val paper = isPaperTheme()
    androidx.compose.material3.Button(
        onClick, modifier, enabled, paperSquare(shape),
        colors ?: if (paper) ButtonDefaults.buttonColors(
            containerColor = PaperTokens.rose, contentColor = PaperTokens.ink,
            disabledContainerColor = PaperTokens.plate2, disabledContentColor = PaperTokens.ink3,
        ) else ButtonDefaults.buttonColors(),
        if (paper) null else elevation,
        border ?: if (paper && enabled) inkRule else null,
        contentPadding, interactionSource, content,
    )
}

@Composable
fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.filledTonalShape,
    colors: ButtonColors? = null,
    elevation: ButtonElevation? = ButtonDefaults.filledTonalButtonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val paper = isPaperTheme()
    androidx.compose.material3.FilledTonalButton(
        onClick, modifier, enabled, paperSquare(shape),
        colors ?: if (paper) ButtonDefaults.filledTonalButtonColors(containerColor = PaperTokens.plate, contentColor = PaperTokens.ink)
            else ButtonDefaults.filledTonalButtonColors(),
        if (paper) null else elevation,
        border ?: if (paper) inkRule else null,
        contentPadding, interactionSource, content,
    )
}

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape,
    colors: ButtonColors? = null,
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val paper = isPaperTheme()
    androidx.compose.material3.OutlinedButton(
        onClick, modifier, enabled, paperSquare(shape),
        colors ?: if (paper) ButtonDefaults.outlinedButtonColors(contentColor = PaperTokens.ink) else ButtonDefaults.outlinedButtonColors(),
        elevation,
        border ?: if (paper) inkRule else ButtonDefaults.outlinedButtonBorder(enabled),
        contentPadding, interactionSource, content,
    )
}

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors? = null,
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    // the site sets text links in its deeper rose so they read on paper
    androidx.compose.material3.TextButton(
        onClick, modifier, enabled, paperSquare(shape),
        colors ?: if (isPaperTheme()) ButtonDefaults.textButtonColors(contentColor = PaperTokens.roseInk) else ButtonDefaults.textButtonColors(),
        elevation, border, contentPadding, interactionSource, content,
    )
}
