package de.foldki.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

enum class PaneCollapse { NONE, FIRST, SECOND }

/**
 * Position of the divider as a fraction (0..1) of the width. 0 = left pane closed, 1 = right pane closed.
 * The value is read only in the layout phase, so dragging does not recompose the pane contents.
 */
@Stable
class SplitPaneState(initialFraction: Float) {
    var fraction by mutableFloatStateOf(initialFraction.coerceIn(0f, 1f))
        internal set

    // Filled in by the layout (plain fields, no recomposition).
    internal var widthPx = 0f
    internal var minPx = 0f
    internal var leftInWindow = 0f
    internal var hingeXInWindow: Float? = null
    internal var anim: Job? = null

    val collapse: PaneCollapse
        get() = when {
            widthPx <= 0f -> if (fraction <= 0f) PaneCollapse.FIRST else if (fraction >= 1f) PaneCollapse.SECOND else PaneCollapse.NONE
            fraction * widthPx < minPx / 2 -> PaneCollapse.FIRST
            fraction * widthPx > widthPx - minPx / 2 -> PaneCollapse.SECOND
            else -> PaneCollapse.NONE
        }

    /** Hinge position relative to this pane, if a vertical fold runs through it. */
    internal val hingeFraction: Float?
        get() = hingeXInWindow?.let { (it - leftInWindow) / widthPx }?.takeIf { widthPx > 0f && it in 0.15f..0.85f }

    /**
     * Ab 4.9: snap points 0 (left closed), 1/3, hinge or 1/2, 2/3, 1 (right closed). A pane narrower than 3/4 of its
     * minimum closes; a quick swipe ([velocity], px/s) goes on in its direction.
     */
    internal fun snapTarget(velocity: Float = 0f): Float {
        val w = widthPx
        if (w <= 0f) return fraction
        val x = fraction * w
        if (x < minPx * 0.75f || (velocity < -1500f && x < w / 2)) return 0f
        if (x > w - minPx * 0.75f || (velocity > 1500f && x > w / 2)) return 1f
        val points = listOf(1f / 3f, hingeFraction ?: 0.5f, 2f / 3f).map { (it * w).coerceIn(minPx, w - minPx) }
        return points.minBy { abs(it - x) } / w
    }

    internal fun defaultTarget(): Float =
        if (widthPx <= 0f) DEFAULT_FRACTION else (DEFAULT_FRACTION * widthPx).coerceIn(minPx, widthPx - minPx) / widthPx

    /** Opens the left pane again if it is closed (e.g. to show the settings there). */
    fun expandFirst() { if (collapse == PaneCollapse.FIRST) fraction = defaultTarget() }

    internal var onSettled: ((Float, PaneCollapse) -> Unit)? = null

    /**
     * Sidebar button (like the Grok Bots app): closes the left pane completely (chat full width),
     * or opens it again at 1/3. Animated; the result is stored like after dragging.
     */
    fun toggleFirst(scope: CoroutineScope) {
        val target = if (collapse == PaneCollapse.FIRST) defaultTarget() else 0f
        anim?.cancel()
        anim = scope.launch {
            animate(fraction, target) { v, _ -> fraction = v }
            onSettled?.invoke(fraction, collapse)
        }
    }

    companion object { const val DEFAULT_FRACTION = 1f / 3f }
}

@Composable
fun rememberSplitPaneState(initialFraction: Float): SplitPaneState = remember { SplitPaneState(initialFraction) }

/**
 * Two panes with a draggable divider (like the Claude/Samsung apps on the Fold):
 * drag to resize, drag past the minimum width to close a pane, drag back to open it,
 * release snaps to 1/3, the hinge (or 1/2) and 2/3, double-tap resets.
 */
@Composable
fun SplitPane(
    state: SplitPaneState,
    modifier: Modifier = Modifier,
    minPaneWidth: Dp = 260.dp,
    handleWidth: Dp = 24.dp,
    onSettled: (fraction: Float, collapse: PaneCollapse) -> Unit = { _, _ -> },
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    val hingeX = rememberVerticalHingeX()
    SideEffect { state.hingeXInWindow = hingeX; state.onSettled = onSettled }
    val scope = rememberCoroutineScope()
    fun settleTo(target: Float) {
        state.anim?.cancel()
        state.anim = scope.launch {
            animate(state.fraction, target) { v, _ -> state.fraction = v }
            onSettled(state.fraction, state.collapse)
        }
    }

    val dragState = rememberDraggableState { delta ->
        if (state.widthPx > 0f) state.fraction = (state.fraction + delta / state.widthPx).coerceIn(0f, 1f)
    }

    val handle: @Composable () -> Unit = {
        Box(
            Modifier.fillMaxHeight()
                .semantics { contentDescription = "Trennlinie verschieben" }
                // Ab 4.9: the Android back gesture at the screen edge must not take the drag (else a closed pane cannot be pulled back).
                .systemGestureExclusion { c -> androidx.compose.ui.geometry.Rect(0f, c.size.height / 2f - 300f, c.size.width.toFloat(), c.size.height / 2f + 300f) }
                .draggable(
                    dragState, Orientation.Horizontal,
                    onDragStarted = { state.anim?.cancel() },
                    onDragStopped = { v -> settleTo(state.snapTarget(v)) },
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        // A tap on the handle of a closed pane opens it again; a double tap resets to the default.
                        onTap = { if (state.collapse != PaneCollapse.NONE) settleTo(state.defaultTarget()) },
                        onDoubleTap = { settleTo(state.defaultTarget()) },
                    )
                },
        ) {
            Box(Modifier.align(Alignment.Center).width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline))
            // A bigger grip, easy to find and to grab – also when a pane is closed (then it sits at the edge).
            Box(
                Modifier.align(Alignment.Center).size(width = 6.dp, height = 56.dp)
                    .clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
    }

    val firstPane: @Composable () -> Unit = { Box(Modifier.fillMaxSize().clipToBounds()) { first() } }
    val secondPane: @Composable () -> Unit = { Box(Modifier.fillMaxSize().clipToBounds()) { second() } }
    Layout(
        contents = listOf(firstPane, handle, secondPane),
        modifier = modifier.onGloballyPositioned { state.leftInWindow = it.boundsInWindow().left },
    ) { (firstM, handleM, secondM), c ->
        val w = c.maxWidth
        val h = c.maxHeight
        val hp = handleWidth.roundToPx().coerceAtMost(w)
        val m = min(minPaneWidth.toPx(), (w - hp) / 2f).coerceAtLeast(0f)
        state.widthPx = w.toFloat(); state.minPx = m
        val x = state.fraction * w // read here (layout phase) -> no recomposition while dragging
        // Settled positions keep the minimum width; while dragging the divider follows the finger all the way.
        val dragging = state.anim?.isActive != true && x > 0f && x < w && (x < m || x > w - m)
        val eff = when {
            x <= 0f -> 0f
            x >= w -> w.toFloat()
            dragging -> x
            else -> x.coerceIn(m, w - m)
        }
        val firstW = (eff - hp / 2f).roundToInt().coerceIn(0, w - hp)
        val secondW = (w - firstW - hp).coerceAtLeast(0)
        val fp = firstM.first().measure(Constraints.fixed(firstW, h))
        val hpl = handleM.first().measure(Constraints.fixed(hp, h))
        val sp = secondM.first().measure(Constraints.fixed(secondW, h))
        layout(w, h) {
            fp.place(0, 0)
            hpl.place(firstW, 0)
            sp.place(firstW + hp, 0)
        }
    }
}

/** x position (window pixels) of a vertical fold/hinge, e.g. the crease of the Galaxy Z Fold, or null. */
@Composable
private fun rememberVerticalHingeX(): Float? {
    val activity = LocalContext.current.findActivity() ?: return null
    val flow = remember(activity) { WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity) }
    val info by flow.collectAsStateWithLifecycle(initialValue = null)
    val fold = info?.displayFeatures?.filterIsInstance<FoldingFeature>()
        ?.firstOrNull { it.orientation == FoldingFeature.Orientation.VERTICAL }
    return fold?.bounds?.exactCenterX()
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
