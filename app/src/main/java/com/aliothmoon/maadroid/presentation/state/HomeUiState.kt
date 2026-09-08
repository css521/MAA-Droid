package com.aliothmoon.maadroid.presentation.state

import com.aliothmoon.maadroid.data.model.update.UpdateProcessState
import com.aliothmoon.maadroid.domain.models.OverlayControlMode
import com.aliothmoon.maadroid.domain.models.RunMode
import com.aliothmoon.maadroid.domain.state.ResourceInitState
import com.aliothmoon.maadroid.common.i18n.UiText

data class HomeUiState(
    val isShowControlOverlay: Boolean = false,
    val isLoading: Boolean = false,
    val resourceUpdateState: UpdateProcessState = UpdateProcessState.Idle,
    val serviceStatusText: UiText = UiText.Empty,
    val serviceStatusColor: StatusColorType = StatusColorType.NEUTRAL,
    val serviceStatusLoading: Boolean = false,
    val remoteServiceActive: Boolean = false,
    val resourceInitState: ResourceInitState = ResourceInitState.NotChecked,
    val runMode: RunMode = RunMode.BACKGROUND,
    val overlayControlMode: OverlayControlMode = OverlayControlMode.FLOAT_BALL,
    val isGranting: Boolean = false,
    val showRunModeUnsupportedDialog: Boolean = false,
    val runModeUnsupportedMessage: UiText = UiText.Empty
)
