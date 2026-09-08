package com.aliothmoon.maadroid.presentation.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.aliothmoon.maadroid.R
import com.aliothmoon.maadroid.data.datasource.ResourceDownloader
import com.aliothmoon.maadroid.data.model.update.UpdateInfo
import com.aliothmoon.maadroid.ui.components.AdaptiveTaskPromptDialog

/**
 * 资源更新确认弹窗
 */
@Composable
fun UpdateConfirmDialog(
    updateInfo: UpdateInfo,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val displayVersion = ResourceDownloader.formatVersionForDisplay(updateInfo.version)

    AdaptiveTaskPromptDialog(
        visible = true,
        title = stringResource(R.string.update_confirm_title_resource),
        message = stringResource(R.string.update_confirm_message_resource, displayVersion),
        onConfirm = onConfirm,
        onDismissRequest = onDismiss,
        confirmText = stringResource(R.string.update_confirm_download_now),
        confirmColor = Color(0xFF4CAF50),
        dismissText = stringResource(R.string.dialog_update_later),
        icon = Icons.Rounded.Info,
        landscapeAdaptive = true
    )
}
