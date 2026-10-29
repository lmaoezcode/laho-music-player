// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.tv

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Ripple alphas on TV: the focused state layer must be visible from the sofa. */
internal val TvRippleAlpha = RippleAlpha(
    draggedAlpha = 0.16f,
    focusedAlpha = 0.32f,
    hoveredAlpha = 0.12f,
    pressedAlpha = 0.24f,
)

/**
 * Hooked once inside `RhythmTheme`. On TV it makes keyboard/D-pad focus clearly visible for the
 * whole app without touching Rhythm's screens:
 *  - Material3 components (buttons, icon buttons, cards, nav items…) get a strong focused ripple;
 *  - plain `Modifier.clickable {}` (which uses [LocalIndication]) also gets a focus ring.
 * On phones it just calls [content].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvFocusStyle(content: @Composable () -> Unit) {
    if (!rememberIsTv()) {
        content()
        return
    }
    val primary = MaterialTheme.colorScheme.primary
    val base = LocalIndication.current
    val indication = remember(base, primary) {
        if (base is IndicationNodeFactory) TvFocusRingIndication(base, primary) else base
    }
    CompositionLocalProvider(
        LocalRippleConfiguration provides RippleConfiguration(color = primary, rippleAlpha = TvRippleAlpha),
        LocalIndication provides indication,
        content = content,
    )
}

/** Wraps the theme's ripple and adds a rounded focus ring while the element is focused. */
private class TvFocusRingIndication(
    private val base: IndicationNodeFactory,
    private val color: Color,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        TvFocusRingNode(interactionSource, color, base.create(interactionSource))

    override fun equals(other: Any?): Boolean =
        other is TvFocusRingIndication && other.base == base && other.color == color

    override fun hashCode(): Int = 31 * base.hashCode() + color.hashCode()
}

private class TvFocusRingNode(
    private val interactionSource: InteractionSource,
    private val color: Color,
    baseNode: DelegatableNode,
) : DelegatingNode(), DrawModifierNode {

    private val focusTracker = FocusInteractionTracker()

    init {
        delegate(baseNode)
    }

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                if (focusTracker.onInteraction(interaction)) invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (focusTracker.isFocused) {
            val stroke = 3.dp.toPx()
            drawRoundRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                cornerRadius = CornerRadius(12.dp.toPx()),
                style = Stroke(width = stroke),
            )
        }
    }
}

/** Counts focus/unfocus interactions; pure so it can be unit-tested. */
internal class FocusInteractionTracker {
    private val active = mutableSetOf<FocusInteraction.Focus>()
    val isFocused: Boolean get() = active.isNotEmpty()

    /** Returns true when [isFocused] changed. */
    fun onInteraction(interaction: Any): Boolean {
        val before = isFocused
        when (interaction) {
            is FocusInteraction.Focus -> active += interaction
            is FocusInteraction.Unfocus -> active -= interaction.focus
        }
        return before != isFocused
    }
}

/**
 * On TV, requests focus for this element when it enters composition (e.g. the play button
 * when the player opens). No-op on phones. Place it before the clickable/focusable modifier.
 */
fun Modifier.tvInitialFocus(): Modifier = composed {
    if (!rememberIsTv()) return@composed Modifier
    val requester = remember { FocusRequester() }
    LaunchedEffect(requester) {
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
    Modifier.focusRequester(requester)
}

/**
 * On TV, makes a non-interactive item (e.g. a chat bubble) focusable with a visible ring so the
 * D-pad can scroll through a list. No-op on phones.
 */
fun Modifier.tvFocusableItem(): Modifier = composed {
    if (!rememberIsTv()) return@composed Modifier
    var focused by remember { mutableStateOf(false) }
    val ring = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent
    Modifier
        .onFocusChanged { focused = it.isFocused }
        .border(3.dp, ring, RoundedCornerShape(16.dp))
        .focusable()
}
