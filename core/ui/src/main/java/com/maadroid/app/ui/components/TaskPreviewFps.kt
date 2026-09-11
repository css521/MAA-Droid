package com.maadroid.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Only a measured FPS is shown; unavailable samples do not become a fabricated zero. */
@Composable
fun BoxScope.TaskPreviewFps(fps: Float?) {
    if (fps == null || !fps.isFinite() || fps < 0f) return
    Text(
        text = "${fps.roundToInt()} FPS",
        color = Color.White.copy(alpha = 0.85f),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}
