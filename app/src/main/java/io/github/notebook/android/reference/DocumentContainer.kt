package io.github.notebook.android

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Kept for readers that want to know whether the open motion has landed. */
internal val LocalDocumentSettled = compositionLocalOf { true }

/** Emphasized decelerate: fast start, gentle landing. */
private val OpenEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val CloseEasing = CubicBezierEasing(0.3f, 0f, 0.2f, 1f)

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/** A rounded window that grows from the tapped card to the whole screen. */
private class RevealShape(val window: Rect, val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
        Outline.Rounded(RoundRect(window, CornerRadius(radius)))
}

/**
 * Opens a note from its card without distorting it.
 *
 * The page keeps its real proportions: a rounded window is revealed from the
 * card's bounds to the full screen (a container transform). Scaling the page
 * non-uniformly stretched titles on open and squashed the page like a closing
 * book on exit.
 *
 * The note's data, parsed Markdown and reading position are prepared off the
 * main thread before [visible] turns true, so the first frame lays out the real
 * page at its last position; every following frame only moves the clip.
 */
@Composable
internal fun DocumentContainer(
    visible: Boolean,
    origin: Rect?,
    backProgress: Float,
    content: @Composable () -> Unit,
) {
    val animation = remember { Animatable(if (visible) 1f else 0f) }
    LaunchedEffect(visible, backProgress) {
        if (visible && backProgress > 0f) animation.snapTo(1f - backProgress.coerceIn(0f, 1f))
        else if (visible) animation.animateTo(1f, tween(320, easing = OpenEasing))
        else animation.animateTo(0f, tween(260, easing = CloseEasing))
    }
    val shown by remember { derivedStateOf { animation.value > 0f } }
    if (!visible && !shown) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.toPx() }
        val height = with(density) { maxHeight.toPx() }
        val full = Rect(0f, 0f, width, height)
        val start = origin ?: Rect(width * .06f, height * .32f, width * .94f, height * .52f)
        val cornerPx = with(density) { 26.dp.toPx() }
        Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black.copy(alpha = .10f * animation.value)) })
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val p = animation.value
                val window = Rect(lerp(start.left, full.left, p), lerp(start.top, full.top, p), lerp(start.right, full.right, p), lerp(start.bottom, full.bottom, p))
                shape = RevealShape(window, cornerPx * (1 - p))
                clip = p < 0.999f
                // Uniform scale only: the page never changes its proportions.
                val scale = lerp(0.94f, 1f, p)
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin((start.center.x / width).coerceIn(0f, 1f), (start.center.y / height).coerceIn(0f, 1f))
                alpha = (p * 2.5f).coerceIn(0f, 1f)
            },
        ) {
            content()
        }
    }
}
