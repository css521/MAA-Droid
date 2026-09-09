package com.aliothmoon.maadroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Fits the same 16:9 preview in either game's viewport; surface ownership stays with its host. */
@Composable
fun TaskPreviewFrame(
    isRunning: Boolean,
    isSurfaceAvailable: Boolean,
    statusLabel: String,
    statusColor: Color,
    pendingMessage: String,
    unavailableMessage: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val ratio = 16f / 9f
        val cardWidth = minOf(maxWidth, maxHeight * ratio)
        Card(
            modifier = Modifier.width(cardWidth).height(cardWidth / ratio)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        ) {
            Box(Modifier.fillMaxSize()) {
                content()
                if (!isRunning || !isSurfaceAvailable) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)),
                        contentAlignment = Alignment.Center) {
                        Text(if (!isRunning) pendingMessage else unavailableMessage,
                            modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(
                    Modifier.align(Alignment.TopEnd).padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor))
                    Spacer(Modifier.width(4.dp))
                    Text(statusLabel, style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
            }
        }
    }
}
