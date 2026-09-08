package com.aliothmoon.maadroid.presentation.state

import com.aliothmoon.maadroid.presentation.view.panel.PanelDialogUiState
import com.aliothmoon.maadroid.presentation.view.panel.PanelTab

data class BackgroundTaskState(
    val selectedNodeId: String? = null,
    val current: PanelTab = PanelTab.TASKS,
    val isFullscreenMonitor: Boolean = false,
    val isEditMode: Boolean = false,
    val isAddingTask: Boolean = false,
    val isProfileMode: Boolean = false,
    val dialog: PanelDialogUiState? = null,
)
