package com.michde.italyitv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme

/**
 * A loud, unmistakable D-pad focus indicator for Android TV — a bright fill plus
 * an accent ring plus a small scale-up. Compose's default focus indication is a
 * faint ripple that vanishes on a dark theme, so leaning-back users could not
 * tell where the selection was.
 *
 * Put this on the element *before* its own `clickable` / `clip` so the ring
 * frames it. `clickable` already makes the element focusable on TV.
 */
@Composable
fun Modifier.tvFocusable(
    shape: Shape = RoundedCornerShape(10.dp),
    scaleUp: Boolean = true,
): Modifier {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused && scaleUp) 1.04f else 1f,
        label = "tvFocusScale",
    )
    val ring = MaterialTheme.colorScheme.primary
    val fill = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
    return this
        .onFocusChanged { focused = it.isFocused }
        .scale(scale)
        .then(
            if (focused) Modifier.background(fill, shape).border(2.5.dp, ring, shape)
            else Modifier,
        )
}
