package com.maadroid.app.presentation.state

import com.maadroid.app.data.model.update.UpdateProcessState
import com.maadroid.app.domain.models.OverlayControlMode
import com.maadroid.app.domain.models.RunMode
import com.maadroid.app.domain.state.ResourceInitState
import com.maadroid.app.common.i18n.UiText

data class HomeUiState(
    val isShowControlOverlay: Boolean = false,
    val isLoading: Boolean = false,
    val resourceUpdateState: UpdateProcessState = UpdateProcessState.Idle,
    val serviceStatusText: UiText = UiText.Empty,
    val resourceFailureDetail: UiText? = null,
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
