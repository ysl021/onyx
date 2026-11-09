package app.onyx.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/**
 * Arithmetic and state for finger-smooth long-press drag reordering inside a
 * plain-Column list of uniform-height rows.
 *
 * The dragged row follows the finger exactly (offset is read at draw time, no
 * recomposition), neighbouring rows slide out of the way via an animated offset,
 * and the underlying list is reordered once per gesture — on the drop — by the
 * caller, so the model never fights the visuals mid-drag.
 */
class ReorderState(
    /** Row height in px for the list this state belongs to. */
    val rowHeightPx: Float,
    /** Current row count (lambda so mid-drag removals stay correct). */
    val rowCount: () -> Int,
) {
    var active by mutableStateOf(false)
        private set
    var from by mutableIntStateOf(-1)
        private set
    var hover by mutableIntStateOf(-1)
        private set
    var dragY by mutableFloatStateOf(0f)
        private set

    fun reset() {
        active = false
        from = -1
        hover = -1
        dragY = 0f
    }

    fun begin(index: Int) {
        active = true
        from = index
        hover = index
        dragY = 0f
    }

    fun drag(dy: Float) {
        if (!active) return
        dragY += dy
        val rows = rowCount()
        if (rows <= 0) return
        hover = (from + (dragY / rowHeightPx).roundToInt()).coerceIn(0, rows - 1)
    }

    /** Ends the gesture; returns from→to pair to commit, or null if nothing moved. */
    fun end(): Pair<Int, Int>? {
        if (!active) return null
        val result = (from to hover).takeIf { it.first != it.second }
        reset()
        return result
    }
}

/**
 * A uniform-height reorderable row. Renders [content] and handles long-press
 * drag itself. Vertical offsets: the dragged row tracks the finger 1:1, rows in
 * the displaced span animate smoothly out of the way.
 */
@Composable
fun ReorderableRow(
    index: Int,
    state: ReorderState,
    rowHeight: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onTap: () -> Unit = {},
    onMove: (from: Int, to: Int) -> Unit,
    content: @Composable () -> Unit,
) {
    val isDragged = state.active && index == state.from
    val targetPx = when {
        !state.active || isDragged -> 0f
        state.from < state.hover && index > state.from && index <= state.hover ->
            -state.rowHeightPx
        state.from > state.hover && index < state.from && index >= state.hover ->
            state.rowHeightPx
        else -> 0f
    }
    val slidePx by animateFloatAsState(targetPx, tween(150), label = "reorderSlide")

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(rowHeight)
            .zIndex(if (isDragged) 1f else 0f)
            .offset {
                IntOffset(
                    0,
                    if (isDragged) state.dragY.roundToInt() else slidePx.roundToInt(),
                )
            }
            .graphicsLayer {
                if (isDragged) {
                    scaleX = 1.035f
                    scaleY = 1.035f
                    alpha = 0.94f
                }
            }
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (enabled) {
                    Modifier.pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { state.begin(index) },
                            onDrag = { change, amount ->
                                change.consume()
                                state.drag(amount.y)
                            },
                            onDragEnd = {
                                val (from, to) = state.end()
                                    ?: return@detectDragGesturesAfterLongPress
                                onMove(from, to)
                            },
                            onDragCancel = { state.end() },
                        )
                    }
                } else {
                    Modifier
                },
            )
            .clickable(enabled = !isDragged) { onTap() },
    ) {
        content()
    }
}

@Composable
fun rememberReorderState(rows: Int, rowHeightPx: Float): ReorderState {
    if (rows <= 0) {
        // A state is cheap; an empty placeholder keeps the remember keys stable.
        return remember { ReorderState(rowHeightPx, { 0 }) }
    }
    return remember(rows) { ReorderState(rowHeightPx) { rows } }.also { it.reset() }
}

/** Non-stable helper: registers a row's bounds for multi-select sweeps. */
fun rowBounds(modifier: Modifier, onBounds: (Rect) -> Unit): Modifier =
    modifier.onGloballyPositioned { onBounds(it.boundsInRoot()) }
