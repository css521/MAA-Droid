package com.maadroid.app.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maadroid.app.R
import com.maadroid.app.data.preferences.AppSettingsManager
import com.maadroid.app.ui.components.AdaptiveTaskPromptDialog
import com.maadroid.app.ui.components.TaskSecondaryButton
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Shared actions and confirmation/settings behavior from the Arknights task page. */
@Composable
fun TaskQuickActionsOverlay(
    onDismissRequest: () -> Unit,
    isGameMuted: Boolean,
    onToggleGameSound: () -> Unit,
    onScreenOff: () -> Unit,
    onShowScreenSaver: () -> Unit,
    onCaptureScreenshot: (() -> Unit)? = null,
    onCloseApp: () -> Unit,
    onExportLogs: () -> Unit,
    gameActionsEnabled: Boolean = true,
    showTouchPreviewSetting: Boolean = true,
    appSettingsManager: AppSettingsManager = koinInject(),
) {
    val coroutineScope = rememberCoroutineScope()
    val muteOnGameLaunch by appSettingsManager.muteOnGameLaunch.collectAsStateWithLifecycle()
    val closeAppOnTaskEnd by appSettingsManager.closeAppOnTaskEnd.collectAsStateWithLifecycle()
    val useHardwareScreenOff by appSettingsManager.useHardwareScreenOff.collectAsStateWithLifecycle()
    val showTouchPreview by appSettingsManager.showTouchPreview.collectAsStateWithLifecycle()
    val debugMode by appSettingsManager.debugMode.collectAsStateWithLifecycle()
    var showHardwareScreenOffConfirm by remember { mutableStateOf(false) }
    // 非空表示静音确认框待确认，确认后执行；仅静音方向弹，解除不弹
    var pendingMuteAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val overlayInteractionSource = remember { MutableInteractionSource() }
    val cardInteractionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = overlayInteractionSource,
                indication = null,
                onClick = onDismissRequest
            )
    ) {
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 64.dp)
                .clickable(
                    interactionSource = cardInteractionSource, indication = null, onClick = {}),
            shape = RoundedCornerShape(4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(10.dp)) {
                // 标题与快速操作组
                Text(
                    text = stringResource(R.string.bg_actions_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ActionTile(
                        icon = Icons.Filled.PowerSettingsNew,
                        label = stringResource(R.string.bg_action_screen_off),
                        onClick = {
                            if (useHardwareScreenOff) onScreenOff() else onShowScreenSaver()
                        },
                        modifier = Modifier.weight(1f),
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                    ActionTile(
                        icon = Icons.AutoMirrored.Filled.ExitToApp,
                        label = stringResource(R.string.bg_action_close_game),
                        onClick = onCloseApp,
                        enabled = gameActionsEnabled,
                        modifier = Modifier.weight(1f),
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ActionTile(
                        enabled = gameActionsEnabled,
                        icon = if (isGameMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        label = if (isGameMuted) stringResource(R.string.bg_action_game_muted)
                        else stringResource(R.string.bg_action_mute_game),
                        onClick = {
                            if (isGameMuted) onToggleGameSound() else pendingMuteAction = onToggleGameSound
                        },
                        modifier = Modifier.weight(1f),
                        containerColor = if (isGameMuted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary,
                        contentColor = if (isGameMuted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(Modifier.height(6.dp))
                ActionTile(
                    icon = Icons.Rounded.FolderZip,
                    label = stringResource(R.string.settings_log_export_chooser_title),
                    onClick = onExportLogs,
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                )

                // 调试模式：截图按钮，保存到 {rootDir}/debug/screenshots
                if (debugMode && onCaptureScreenshot != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ActionTile(
                            icon = Icons.Filled.Screenshot,
                            label = stringResource(R.string.bg_action_screenshot),
                            onClick = onCaptureScreenshot,
                            modifier = Modifier.weight(1f),
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp
                )
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.bg_auto_settings_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                SettingSwitchRow(
                    icon = Icons.Filled.NotificationsPaused,
                    label = stringResource(R.string.bg_auto_mute_on_launch),
                    checked = muteOnGameLaunch,
                    onCheckedChange = { checked ->
                        val apply = {
                            coroutineScope.launch { appSettingsManager.setMuteOnGameLaunch(checked) }
                            Unit
                        }
                        if (checked) pendingMuteAction = apply else apply()
                    })
                SettingSwitchRow(
                    icon = Icons.Filled.Cancel,
                    label = stringResource(R.string.bg_auto_close_on_end),
                    checked = closeAppOnTaskEnd,
                    onCheckedChange = {
                        coroutineScope.launch { appSettingsManager.setCloseAppOnTaskEnd(it) }
                    })
                SettingSwitchRow(
                    icon = Icons.Filled.StayCurrentPortrait,
                    label = stringResource(R.string.bg_auto_hardware_screen_off),
                    checked = useHardwareScreenOff,
                    onCheckedChange = { checked ->
                        if (checked) {
                            showHardwareScreenOffConfirm = true
                        } else {
                            coroutineScope.launch {
                                appSettingsManager.setUseHardwareScreenOff(
                                    false
                                )
                            }
                        }
                    })
                if (showTouchPreviewSetting) SettingSwitchRow(
                    icon = Icons.Filled.TouchApp,
                    label = stringResource(R.string.bg_auto_show_touch_preview),
                    checked = showTouchPreview,
                    onCheckedChange = {
                        coroutineScope.launch { appSettingsManager.setShowTouchPreview(it) }
                    })
            }
        }
    }

    pendingMuteAction?.let { action ->
        AdaptiveTaskPromptDialog(
            visible = true,
            title = stringResource(R.string.dialog_mute_game_title),
            message = AnnotatedString(stringResource(R.string.dialog_mute_game_message)),
            onDismissRequest = { pendingMuteAction = null },
            onConfirm = {
                pendingMuteAction = null
                action()
            },
            confirmText = stringResource(R.string.dialog_mute_game_confirm),
            dismissText = stringResource(R.string.common_cancel),
            icon = Icons.AutoMirrored.Filled.VolumeOff,
            iconTint = MaterialTheme.colorScheme.primary,
            confirmColor = MaterialTheme.colorScheme.primary,
        )
    }

    if (showHardwareScreenOffConfirm) {
        AdaptiveTaskPromptDialog(
            visible = true,
            title = stringResource(R.string.dialog_hardware_screen_off_title),
            message = AnnotatedString(stringResource(R.string.dialog_hardware_screen_off_message)),
            onDismissRequest = { showHardwareScreenOffConfirm = false },
            onConfirm = {
                showHardwareScreenOffConfirm = false
                coroutineScope.launch { appSettingsManager.setUseHardwareScreenOff(true) }
            },
            confirmText = stringResource(R.string.common_confirm),
            dismissText = stringResource(R.string.common_cancel),
            icon = Icons.Filled.PowerSettingsNew,
            iconTint = MaterialTheme.colorScheme.primary,
            confirmColor = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ActionTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color,
    enabled: Boolean = true,
    contentColor: Color
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(36.dp),
        shape = RoundedCornerShape(4.dp),
        color = containerColor.copy(alpha = 0.08f),
        contentColor = contentColor,
        border = BorderStroke(0.5.dp, containerColor.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = containerColor.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun SettingSwitchRow(
    icon: ImageVector, label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun TaskQuickActionsButton(expanded: Boolean, onClick: () -> Unit) {
    TaskSecondaryButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 14.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(1.dp, if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
    ) {
        Icon(Icons.Filled.MoreVert, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.task_btn_quick_options), maxLines = 1)
    }
}
