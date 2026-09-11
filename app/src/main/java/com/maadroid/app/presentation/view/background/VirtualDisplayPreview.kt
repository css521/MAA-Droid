package com.maadroid.app.presentation.view.background

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maadroid.app.R
import com.maadroid.app.ui.components.TaskPreviewFrame
import com.maadroid.app.domain.service.AppWatchdog
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
    val (dotColor, label) = when (status) {
        VirtualDisplayPreviewStatus.RUNNING -> Color(0xFF4CAF50) to stringResource(R.string.virtual_display_game_running)
        VirtualDisplayPreviewStatus.STOPPED -> Color(0xFFF44336) to stringResource(R.string.virtual_display_game_stopped)
        VirtualDisplayPreviewStatus.IDLE -> Color(0xFF9E9E9E) to stringResource(R.string.virtual_display_idle)
    }
    TaskPreviewFrame(
        modifier = modifier,
        isRunning = isRunning,
        isSurfaceAvailable = isSurfaceAvailable,
        statusLabel = label,
        statusColor = dotColor,
        pendingMessage = stringResource(R.string.virtual_display_pending),
        unavailableMessage = unavailableMessage ?: stringResource(R.string.virtual_display_waiting_surface),
        onClick = onClick,
        content = content,
    )
}
