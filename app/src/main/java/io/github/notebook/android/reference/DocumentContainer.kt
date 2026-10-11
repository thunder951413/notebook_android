package io.github.notebook.android

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Expands the tapped card's geometry into the document without coupling saving to motion. */
@Composable
internal fun DocumentContainer(
    visible: Boolean,
    origin: Rect?,
    backProgress: Float,
    content: @Composable () -> Unit,
) {
    val animation = remember { Animatable(0f) }
    LaunchedEffect(visible, backProgress) {
        if (visible && backProgress > 0f) animation.snapTo(1f - backProgress.coerceIn(0f, 1f))
        else animation.animateTo(if (visible) 1f else 0f, tween(if (visible) 320 else 280))
    }
    val p = animation.value
    if (visible || p > 0f)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val width = with(density) { maxWidth.toPx() }
            val height = with(density) { maxHeight.toPx() }
            val rect = origin ?: Rect(width * .05f, height * .3f, width * .95f, height * .55f)
            val startX = (rect.width / width).coerceIn(.1f, 1f)
            val startY = (rect.height / height).coerceIn(.08f, 1f)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .08f * p)))
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        scaleX = startX + (1 - startX) * p
                        scaleY = startY + (1 - startY) * p
                        translationX = (rect.center.x - width / 2) * (1 - p)
                        translationY = (rect.center.y - height / 2) * (1 - p)
                        alpha = (p * 3).coerceIn(0f, 1f)
                    }
                    .clip(RoundedCornerShape((26 * (1 - p)).dp))
            ) {
                content()
            }
        }
}
