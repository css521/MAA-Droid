package com.maadroid.app.presentation.view.engine

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maadroid.app.engine.TaskPanelSpec

/**
 * 按引擎声明渲染任务列表。
 *
 * **本文件不 import 任何引擎的东西** —— 它只认 [TaskPanelSpec]。这是「一个 App 控多个
 * 游戏」在 UI 层的落点：引擎新增一个任务，宿主一行都不用改。
 *
 * 与方舟现有的 `BackgroundTaskView` 并存而非替换它。方舟那条路径有 1,300 行、25 个回调，
 * 且绑着 `TaskChainNode` / profiles / clientType；在 `core:ui` 与 `TaskChainState` 拆分
 * 之前动它是纯风险。等方舟收拢为 `AutomationEngine` 后，它会迁到这里来。
 *
 * @param panels 引擎声明的面板，顺序即执行次序（引擎作者定的，不是用户勾选顺序）
 * @param enabledOf 该任务当前是否勾选；未配置过时应回落到 [TaskPanelSpec.enabledByDefault]
 * @param paramsOf 该任务的参数 JSON，宿主原样存取、不解释其结构
 */
@Composable
fun EngineTaskList(
    panels: List<TaskPanelSpec>,
    enabledOf: (TaskPanelSpec) -> Boolean,
    paramsOf: (TaskPanelSpec) -> String,
    onEnabledChange: (TaskPanelSpec, Boolean) -> Unit,
    onParamsChange: (TaskPanelSpec, String) -> Unit,
    expandedTaskType: String?,
    onToggleExpand: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
    ) {
        items(panels, key = { it.taskType }) { panel ->
            EngineTaskCard(
                panel = panel,
                enabled = enabledOf(panel),
                params = paramsOf(panel),
                expanded = expandedTaskType == panel.taskType,
                onEnabledChange = { onEnabledChange(panel, it) },
                onParamsChange = { onParamsChange(panel, it) },
                onToggleExpand = {
                    onToggleExpand(if (expandedTaskType == panel.taskType) null else panel.taskType)
                },
            )
        }
    }
}

@Composable
private fun EngineTaskCard(
    panel: TaskPanelSpec,
    enabled: Boolean,
    params: String,
    expanded: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onParamsChange: (String) -> Unit,
    onToggleExpand: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 勾选与展开是两件事：点标题看配置不该改变是否要跑
                Checkbox(checked = enabled, onCheckedChange = onEnabledChange)
                Text(text = stringResource(panel.titleRes))
            }
            if (expanded) {
                HorizontalDivider()
                Column(modifier = Modifier.padding(12.dp)) {
                    // 面板内容由引擎提供；参数 JSON 进来、改完出去，宿主不看内容
                    panel.content(params, onParamsChange)
                }
            }
        }
    }
}
