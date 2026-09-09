package com.aliothmoon.maadroid.presentation.view.background

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliothmoon.maadroid.R
import com.aliothmoon.maadroid.domain.service.AppWatchdog
import org.koin.compose.koinInject

enum class VirtualDisplayPreviewStatus { IDLE, RUNNING, STOPPED }

@Composable
fun VirtualDisplayPreview(
    modifier: Modifier = Modifier,
    isRunning: Boolean,
    isSurfaceAvailable: Boolean,
    onClick: () -> Unit,
    appWatchdog: AppWatchdog = koinInject(),
    content: @Composable () -> Unit
) {
    val watchdogState by appWatchdog.state.collectAsStateWithLifecycle()
    VirtualDisplayPreview(
        modifier = modifier,
        isRunning = isRunning,
        isSurfaceAvailable = isSurfaceAvailable,
        status = when (watchdogState) {
            AppWatchdog.WatchdogState.WATCHING -> VirtualDisplayPreviewStatus.RUNNING
            AppWatchdog.WatchdogState.APP_DIED -> VirtualDisplayPreviewStatus.STOPPED
            AppWatchdog.WatchdogState.IDLE -> VirtualDisplayPreviewStatus.IDLE
        },
        onClick = onClick,
        content = content,
    )
}

/** Shared preview chrome. Other engines supply their own state instead of the Arknights watchdog. */
@Composable
fun VirtualDisplayPreview(
    modifier: Modifier = Modifier,
    isRunning: Boolean,
    isSurfaceAvailable: Boolean,
    status: VirtualDisplayPreviewStatus,
    onClick: (() -> Unit)? = null,
    unavailableMessage: String? = null,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val maxWidth = maxWidth
        val maxHeight = maxHeight

        val aspectRatio = 16f / 9f
        val widthFromHeight = maxHeight * aspectRatio
        val heightFromWidth = maxWidth / aspectRatio

        val (cardWidth, cardHeight) = if (widthFromHeight <= maxWidth) {
            widthFromHeight to maxHeight
        } else {
            maxWidth to heightFromWidth
        }

        Card(
            modifier = Modifier
                .width(cardWidth)
                .height(cardHeight)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
            shape = MaterialTheme.shapes.medium,
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                content()

                when {
                    !isRunning -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.virtual_display_pending),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    !isSurfaceAvailable -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = unavailableMessage ?: stringResource(R.string.virtual_display_waiting_surface),
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                val (dotColor, label) = when (status) {
                    VirtualDisplayPreviewStatus.RUNNING -> {
                        Color(0xFF4CAF50) to stringResource(R.string.virtual_display_game_running)
                    }

                    VirtualDisplayPreviewStatus.STOPPED -> {
                        Color(0xFFF44336) to stringResource(R.string.virtual_display_game_stopped)
                    }

                    VirtualDisplayPreviewStatus.IDLE -> {
                        Color(0xFF9E9E9E) to stringResource(R.string.virtual_display_idle)
                    }
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(
                            Color.Black.copy(alpha = 0.5f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }
        }
    }
}
