package com.aurora.music.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.aurora.music.util.MotionTokens
import com.aurora.music.util.SpaceTokens
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// one set of motion specs: enters decelerate, exits are shorter and accelerate
object Motion {
    val EaseOut = MotionTokens.EASE_OUT.let { CubicBezierEasing(it[0], it[1], it[2], it[3]) }
    val EaseIn = MotionTokens.EASE_IN.let { CubicBezierEasing(it[0], it[1], it[2], it[3]) }
    val EaseInOut = MotionTokens.EASE_IN_OUT.let { CubicBezierEasing(it[0], it[1], it[2], it[3]) }
    val Emphasized = MotionTokens.EASE_EMPHASIZED.let { CubicBezierEasing(it[0], it[1], it[2], it[3]) }

    fun <T> enter(ms: Int = MotionTokens.STANDARD): TweenSpec<T> = tween(ms, easing = EaseOut)
    fun <T> exit(ms: Int = MotionTokens.FAST): TweenSpec<T> = tween(ms, easing = EaseIn)
    fun <T> state(ms: Int = MotionTokens.FAST): TweenSpec<T> = tween(ms, easing = EaseInOut)
    val Pop = spring<Float>(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium)
}

object Space {
    val xs = SpaceTokens.XS.dp
    val s = SpaceTokens.S.dp
    val m = SpaceTokens.M.dp
    val l = SpaceTokens.L.dp
    val gutter = SpaceTokens.GUTTER.dp
    val xl = SpaceTokens.XL.dp
    val section = SpaceTokens.SECTION.dp
}

// cards and tiles sink and dim under the finger; the node is only created on first touch
object PressScaleIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressScaleNode(interactionSource)
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = 0x5ca1e
}

private class PressScaleNode(private val source: InteractionSource) :
    Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private val pressed = Animatable(0f)
    private var focused = false

    override fun onAttach() {
        coroutineScope.launch {
            // only a new press event may interrupt the dip, so a hover or focus change never strands it half way
            var dip: Job? = null
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        dip?.cancel()
                        dip = launch { pressed.animateTo(1f, tween(MotionTokens.INSTANT)) }
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        dip?.cancel()
                        dip = launch {
                            // a quick tap still shows the whole dip
                            pressed.animateTo(1f, tween(MotionTokens.INSTANT))
                            pressed.animateTo(0f, Motion.state(MotionTokens.STANDARD))
                        }
                    }
                    is FocusInteraction.Focus -> { focused = true; invalidateDraw() }
                    is FocusInteraction.Unfocus -> { focused = false; invalidateDraw() }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val p = pressed.value
        if (p == 0f && !focused) {
            drawContent()
            return
        }
        scale(1f - (1f - MotionTokens.PRESS_SCALE) * p) {
            this@draw.drawContent()
            drawRect(currentValueOf(LocalContentColor).copy(alpha = MotionTokens.PRESS_DIM * maxOf(p, if (focused) 1f else 0f)))
        }
    }
}

// clickable with the card press feedback; goes after the clip
fun Modifier.pressClickable(enabled: Boolean = true, onClickLabel: String? = null, role: Role? = null, onClick: () -> Unit): Modifier =
    clickable(interactionSource = null, indication = PressScaleIndication, enabled = enabled, onClickLabel = onClickLabel, role = role, onClick = onClick)

// fades and lifts the content in whenever key changes; the first composition is left alone
@Composable
fun Modifier.screenEnter(key: Any?): Modifier {
    val progress = remember { Animatable(1f) }
    var shown by remember { mutableStateOf(key) }
    LaunchedEffect(key) {
        if (shown == key) return@LaunchedEffect
        progress.snapTo(0f)
        shown = key
        progress.animateTo(1f, Motion.enter())
    }
    val rise = with(LocalDensity.current) { MotionTokens.ENTER_RISE_DP.dp.toPx() }
    return graphicsLayer {
        // hidden for the frame before the effect starts so the new page never flashes at full strength
        val p = if (shown != key) 0f else progress.value
        alpha = p
        translationY = (1f - p) * rise
    }
}

// a small spring pop when liked turns on for the same key
@Composable
fun Modifier.likePop(liked: Boolean, key: Any?): Modifier {
    val scale = remember(key) { Animatable(1f) }
    var last by remember(key) { mutableStateOf(liked) }
    LaunchedEffect(key, liked) {
        if (liked && !last) {
            scale.snapTo(0.72f)
            scale.animateTo(1f, Motion.Pop)
        }
        last = liked
    }
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}
