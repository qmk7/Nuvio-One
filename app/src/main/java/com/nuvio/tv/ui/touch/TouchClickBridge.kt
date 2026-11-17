package com.nuvio.tv.ui.touch

import android.util.Log
import android.view.View
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.nuvio.tv.core.device.LocalDeviceType

/**
 * QR Time on phones and tablets: makes the TV screens answer to touch.
 *
 * The TV components (androidx.tv.material3 Surface, Card, Button...) only react to the remote's
 * OK key; they ignore finger taps. Rather than rewriting every one of them, this modifier sits at
 * the root of a window and, when a tap lands on something that did not handle it itself, finds
 * the clickable element under the finger through the semantics tree, focuses it and clicks it
 * (long press: its long click), exactly as the remote would.
 *
 * Touch-native components (Modifier.clickable, scrolling lists, sliders, text fields) consume
 * their own pointer events first, so they keep working as before and are never clicked twice.
 * On TV the modifier does nothing.
 */
fun Modifier.touchClickBridge(): Modifier = composed {
    if (!LocalDeviceType.current.isTouch) return@composed this
    val view = LocalView.current
    val coordinates = remember { CoordinatesHolder() }
    this
        .onGloballyPositioned { coordinates.value = it }
        .pointerInput(view) {
            awaitEachGesture {
                // Main pass on the way up: children (clickables, scrollers) have already seen it.
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                if (down.isConsumed) return@awaitEachGesture
                val slop = viewConfiguration.touchSlop
                var outcome = Outcome.HELD
                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (outcome == Outcome.HELD) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        outcome = when {
                            change == null || change.isConsumed -> Outcome.CANCELLED
                            (change.position - down.position).getDistance() > slop -> Outcome.CANCELLED
                            change.changedToUp() -> Outcome.TAPPED
                            else -> Outcome.HELD
                        }
                    }
                }
                if (outcome == Outcome.CANCELLED) return@awaitEachGesture
                val windowPoint = coordinates.value?.takeIf { it.isAttached }
                    ?.localToWindow(down.position) ?: return@awaitEachGesture
                if (outcome == Outcome.TAPPED) {
                    if (!performAt(view, windowPoint, longPress = false)) {
                        // Nothing clickable there: an empty-area tap (e.g. the player's video).
                        TouchBackgroundTap.dispatch()
                    }
                } else if (performAt(view, windowPoint, longPress = true)) {
                    // Held past the long-press timeout without moving: long click, then swallow
                    // the rest of the gesture so lifting the finger doesn't click as well.
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) break
                    }
                }
            }
        }
}

/**
 * Handlers for taps on empty areas, latest registered first. The player uses it so tapping the
 * video shows or hides its controls, like pressing OK on the remote.
 */
object TouchBackgroundTap {
    private val handlers = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    internal fun dispatch(): Boolean {
        val handler = handlers.lastOrNull() ?: return false
        runCatching { handler() }.onFailure { Log.w(TAG, "background tap failed", it) }
        return true
    }

    /** While this is in the composition, empty-area taps call [onTap] (touch devices only). */
    @Composable
    fun Register(onTap: () -> Unit) {
        if (!LocalDeviceType.current.isTouch) return
        val latest = androidx.compose.runtime.rememberUpdatedState(onTap)
        androidx.compose.runtime.DisposableEffect(Unit) {
            val handler: () -> Unit = { latest.value() }
            handlers.add(handler)
            onDispose { handlers.remove(handler) }
        }
    }
}

private enum class Outcome { HELD, TAPPED, CANCELLED }

private class CoordinatesHolder {
    var value: LayoutCoordinates? = null
}

/** Same as [touchClickBridge], as a wrapper for window roots such as dialogs. */
@Composable
fun TouchClickBridgeRoot(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(modifier = modifier.touchClickBridge()) { content() }
}

private const val TAG = "TouchClickBridge"

/** Clicks (or long-clicks) the topmost clickable element at [windowPoint]. True if one was found. */
private fun performAt(view: View, windowPoint: Offset, longPress: Boolean): Boolean {
    val root = (view as? RootForTest)?.semanticsOwner?.unmergedRootSemanticsNode ?: return false
    val target = runCatching { findTarget(root, windowPoint, longPress) }
        .onFailure { Log.w(TAG, "hit test failed", it) }
        .getOrNull() ?: return false
    val config = target.config
    runCatching { config.getOrNull(SemanticsActions.RequestFocus)?.action?.invoke() }
    val action = if (longPress) {
        config.getOrNull(SemanticsActions.OnLongClick)?.action
            ?: config.getOrNull(SemanticsActions.OnClick)?.action
    } else {
        config.getOrNull(SemanticsActions.OnClick)?.action
    }
    return runCatching { action?.invoke() == true }
        .onFailure { Log.w(TAG, "click failed", it) }
        .getOrDefault(false)
}

/**
 * Topmost node under the point that can be clicked. Children are searched last-drawn first, so an
 * overlay (side menu, popup, scrim) wins over what lies beneath it. Nodes that are not placed,
 * hidden or disabled are skipped.
 */
private fun findTarget(node: SemanticsNode, point: Offset, longPress: Boolean): SemanticsNode? {
    if (!node.layoutInfo.isPlaced) return null
    if (node.config.getOrNull(SemanticsProperties.InvisibleToUser) != null) return null
    val children = node.children
    for (i in children.indices.reversed()) {
        findTarget(children[i], point, longPress)?.let { return it }
    }
    val config = node.config
    val clickable = config.getOrNull(SemanticsActions.OnClick) != null ||
        (longPress && config.getOrNull(SemanticsActions.OnLongClick) != null)
    if (!clickable) return null
    if (config.getOrNull(SemanticsProperties.Disabled) != null) return null
    return if (node.boundsInWindow.contains(point)) node else null
}

/**
 * Drop-in for androidx.compose.ui.window.Dialog: a dialog is its own window, so it needs its own
 * touch bridge for its TV buttons to answer taps on phones and tablets. Same as the original on TV.
 */
@Composable
fun Dialog(
    onDismissRequest: () -> Unit,
    properties: androidx.compose.ui.window.DialogProperties = androidx.compose.ui.window.DialogProperties(),
    content: @Composable () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        TouchClickBridgeRoot(content = content)
    }
}
