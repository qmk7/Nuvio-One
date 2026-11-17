package com.nuvio.tv.ui.touch

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import com.nuvio.tv.core.device.LocalDeviceType
import kotlin.math.abs

/** One screen width of horizontal swipe moves the video this far. */
private const val SWIPE_FULL_WIDTH_MS = 5 * 60_000L

/** Double tap on the left / right third of the video jumps this far back / forward. */
private const val DOUBLE_TAP_SEEK_MS = 10_000L

/**
 * QR Time on phones and tablets: finger seeking on the video, on top of the touch bridge's
 * "tap toggles the controls".
 *
 * - Swipe left/right: scrub (the same preview the remote's left/right keys show), let go to jump.
 *   One screen width is [SWIPE_FULL_WIDTH_MS].
 * - Double tap on the right / left third: forward / back [DOUBLE_TAP_SEEK_MS].
 *
 * Single taps are left untouched, so the touch bridge still clicks the control under the finger
 * or toggles the controls. Does nothing on TV or while [enabled] is false (panels, dialogs open).
 */
fun Modifier.playerTouchGestures(
    enabled: Boolean,
    onPreviewSeekBy: (Long) -> Unit,
    onCommitPreviewSeek: () -> Unit,
    onSeekBy: (Long) -> Unit
): Modifier = composed {
    if (!LocalDeviceType.current.isTouch) return@composed this
    val isEnabled by rememberUpdatedState(enabled)
    val preview by rememberUpdatedState(onPreviewSeekBy)
    val commit by rememberUpdatedState(onCommitPreviewSeek)
    val seekBy by rememberUpdatedState(onSeekBy)
    var lastTapUpTime = 0L
    var lastTapPosition = Offset.Zero
    this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
            if (!isEnabled || down.isConsumed) return@awaitEachGesture
            val width = size.width.coerceAtLeast(1)
            val slop = viewConfiguration.touchSlop
            val doubleTapWindow = viewConfiguration.doubleTapTimeoutMillis
            val isSecondTap = down.uptimeMillis - lastTapUpTime <= doubleTapWindow &&
                (down.position - lastTapPosition).getDistance() < slop * 4
            if (isSecondTap) {
                // Claim the second tap so the bridge doesn't also toggle the controls back.
                down.consume()
            }

            var scrubbing = false
            var lastX = down.position.x
            var carryMs = 0.0
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!scrubbing && !change.isConsumed) {
                    val delta = change.position - down.position
                    if (abs(delta.x) > slop && abs(delta.x) > abs(delta.y) * 1.5f) {
                        scrubbing = true
                        lastX = change.position.x
                    }
                }
                if (scrubbing) {
                    change.consume()
                    val dx = change.position.x - lastX
                    lastX = change.position.x
                    carryMs += dx / width * SWIPE_FULL_WIDTH_MS
                    val step = carryMs.toLong()
                    if (step != 0L) {
                        carryMs -= step
                        preview(step)
                    }
                }
                if (change.changedToUp() || !change.pressed) {
                    if (scrubbing) {
                        commit()
                        lastTapUpTime = 0L
                    } else if (isSecondTap) {
                        change.consume()
                        val third = width / 3f
                        when {
                            down.position.x >= width - third -> seekBy(DOUBLE_TAP_SEEK_MS)
                            down.position.x <= third -> seekBy(-DOUBLE_TAP_SEEK_MS)
                        }
                        lastTapUpTime = 0L
                    } else {
                        lastTapUpTime = change.uptimeMillis
                        lastTapPosition = down.position
                    }
                    break
                }
            }
        }
    }
}
