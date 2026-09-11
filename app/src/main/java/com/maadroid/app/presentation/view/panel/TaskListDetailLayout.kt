package com.maadroid.app.presentation.view.panel

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.maadroid.app.data.model.TaskChainNode
import com.maadroid.app.data.model.TaskParamProvider
import com.maadroid.app.data.model.TaskProfile
import com.maadroid.app.data.model.TaskTypeInfo
import com.maadroid.app.ui.components.TaskListDetailScaffold

@Composable
fun TaskListDetailLayout(
    nodes: List<TaskChainNode>,
    selectedNode: TaskChainNode?,
    selectedNodeId: String?,
    isEditMode: Boolean,
    isAddingTask: Boolean,
    isProfileMode: Boolean,
    profiles: List<TaskProfile>,
    activeProfileId: String,
    clientType: String,
    onNodeEnabledChange: (String, Boolean) -> Unit,
    onNodeSelected: (String) -> Unit,
    onNodeMove: (Int, Int) -> Unit,
    onToggleEditMode: () -> Unit,
    onToggleAddingTask: () -> Unit,
    onToggleProfileMode: () -> Unit,
    onConfigChange: (TaskParamProvider) -> Unit,
    onAddNode: (TaskTypeInfo) -> Unit,
    onRemoveNode: (String) -> Unit,
    onDuplicateNode: (String) -> Unit,
    onRenameNode: (String, String) -> Unit,
    onSwitchProfile: (String) -> Unit,
    onRenameProfile: (String, String) -> Unit,
    onDuplicateProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onCreateProfile: () -> Unit,
    onReorderProfile: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    /** 后台任务页右侧配置区包一层 Card；悬浮窗已有外层 Card 时关闭 */
    wrapDetailInCard: Boolean = false,
) {
    TaskListDetailScaffold(
        modifier = modifier,
        wrapDetailInCard = wrapDetailInCard,
        taskList = { listModifier ->
            TaskListPanel(
                nodes = nodes,
                selectedNodeId = selectedNodeId,
                isEditMode = isEditMode,
                isAddingTask = isAddingTask,
                isProfileMode = isProfileMode,
                onNodeEnabledChange = onNodeEnabledChange,
                onNodeSelected = onNodeSelected,
                onNodeMove = onNodeMove,
                onToggleEditMode = onToggleEditMode,
                onToggleAddingTask = onToggleAddingTask,
                onToggleProfileMode = onToggleProfileMode,
                modifier = listModifier,
            )
        },
    ) {
        TaskConfigPanel(
            selectedNode = selectedNode,
            isEditMode = isEditMode,
            isAddingTask = isAddingTask,
            isProfileMode = isProfileMode,
            profiles = profiles,
            activeProfileId = activeProfileId,
            clientType = clientType,
            onConfigChange = onConfigChange,
            onAddNode = onAddNode,
            onRemoveNode = onRemoveNode,
            onDuplicateNode = onDuplicateNode,
            onRenameNode = onRenameNode,
            onSwitchProfile = onSwitchProfile,
            onRenameProfile = onRenameProfile,
            onDuplicateProfile = onDuplicateProfile,
            onDeleteProfile = onDeleteProfile,
            onCreateProfile = onCreateProfile,
            onReorderProfile = onReorderProfile,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
