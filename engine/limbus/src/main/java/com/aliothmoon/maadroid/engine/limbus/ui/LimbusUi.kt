package com.aliothmoon.maadroid.engine.limbus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aliothmoon.maadroid.engine.EngineUi
import com.aliothmoon.maadroid.engine.TaskPanelSpec
import com.aliothmoon.maadroid.engine.limbus.LimbusTask
import com.aliothmoon.maadroid.engine.limbus.R

/**
 * 边狱引擎的 UI 插槽。
 *
 * 这是 [EngineUi.taskPanels] 的**第一个真实实现** —— 在此之前两个引擎的 provider 都返回
 * `emptyList()`，也就是说这个契约从未被验证过。刻意先用边狱（约 8k 行）而非方舟
 * （13k 行面板 + 254 个组件调用点）来跑通它：契约若有问题，在这里发现的代价小得多。
 *
 * 面板只用 material3 原生组件，**不碰 `:app` 里的自定义组件库**。这既是当前的硬约束
 * （`ModuleBoundaryContractTest` 禁止引擎依赖宿主应用层），也顺带回答了
 * `core:ui` 该收哪些东西：真正被引擎需要的组件，才值得下沉。
 */
object LimbusUi : EngineUi {

    override val taskPanels: List<TaskPanelSpec> = listOf(
        TaskPanelSpec(
            taskType = LimbusTask.MAIL.type,
            titleRes = R.string.limbus_task_mail,
            content = { _, _ -> NoOptions() },
        ),
        TaskPanelSpec(
            taskType = LimbusTask.EXP.type,
            titleRes = R.string.limbus_task_exp,
            content = { params, onChange ->
                LuxcavationPanel(
                    section = LimbusTask.EXP.configSection!!,
                    stageKey = STAGE_KEY_EXP,
                    paramsJson = params,
                    onChange = onChange,
                )
            },
        ),
        TaskPanelSpec(
            taskType = LimbusTask.THREAD.type,
            titleRes = R.string.limbus_task_thread,
            content = { params, onChange ->
                LuxcavationPanel(
                    section = LimbusTask.THREAD.configSection!!,
                    stageKey = STAGE_KEY_THREAD,
                    paramsJson = params,
                    onChange = onChange,
                )
            },
        ),
        TaskPanelSpec(
            taskType = LimbusTask.MIRROR.type,
            titleRes = R.string.limbus_task_mirror,
            content = { params, onChange -> MirrorPanel(params, onChange) },
        ),
        TaskPanelSpec(
            taskType = LimbusTask.REWARD.type,
            titleRes = R.string.limbus_task_reward,
            content = { _, _ -> NoOptions() },
        ),
    )

    /**
     * 经验 / 纺锤副本共用一套设置：关卡 + 进入方式。
     *
     * 键名 `exp_stage` / `thread_stage` / `luxcavation_mode` 与取值 `enter` /
     * `skip battle` 全部照抄上游配置 —— 动作代码按字符串比对，改一个字母就静默失效
     * （`exec_exp_select_stage` 遇到未知 mode 会直接放弃选关）。
     */
    @Composable
    private fun LuxcavationPanel(
        section: String,
        stageKey: String,
        paramsJson: String,
        onChange: (String) -> Unit,
    ) {
        val params = TaskParams.parse(paramsJson)
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = params.str(section, stageKey, DEFAULT_STAGE),
                onValueChange = { onChange(params.with(section, stageKey, it)) },
                label = { Text(stringResource(R.string.limbus_stage_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ModeChips(
                current = params.str(section, MODE_KEY, MODE_ENTER),
                onSelect = { onChange(params.with(section, MODE_KEY, it)) },
            )
        }
    }

    @Composable
    private fun ModeChips(current: String, onSelect: (String) -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.limbus_mode_label))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = current == MODE_ENTER,
                    onClick = { onSelect(MODE_ENTER) },
                    label = { Text(stringResource(R.string.limbus_mode_enter)) },
                )
                FilterChip(
                    selected = current == MODE_SKIP,
                    onClick = { onSelect(MODE_SKIP) },
                    label = { Text(stringResource(R.string.limbus_mode_skip)) },
                )
            }
        }
    }

    /** 镜牢：难度 + 是否领奖。其余（队伍轮换、饰品名单、卡包权重）待后续补 */
    @Composable
    private fun MirrorPanel(paramsJson: String, onChange: (String) -> Unit) {
        val params = TaskParams.parse(paramsJson)
        val section = LimbusTask.MIRROR.configSection!!
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.limbus_mirror_mode_label))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = params.str(section, MIRROR_MODE_KEY, MIRROR_NORMAL) == MIRROR_NORMAL,
                        onClick = { onChange(params.with(section, MIRROR_MODE_KEY, MIRROR_NORMAL)) },
                        label = { Text(stringResource(R.string.limbus_mirror_mode_normal)) },
                    )
                    FilterChip(
                        selected = params.str(section, MIRROR_MODE_KEY, MIRROR_NORMAL) == MIRROR_HARD,
                        onClick = { onChange(params.with(section, MIRROR_MODE_KEY, MIRROR_HARD)) },
                        label = { Text(stringResource(R.string.limbus_mirror_mode_hard)) },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.limbus_mirror_accept_reward))
                Switch(
                    checked = params.bool(section, ACCEPT_REWARD_KEY, true),
                    onCheckedChange = { onChange(params.with(section, ACCEPT_REWARD_KEY, it)) },
                )
            }
        }
    }

    @Composable
    private fun NoOptions() {
        Text(
            text = stringResource(R.string.limbus_no_options),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        )
    }

    // 以下键名与取值照抄上游配置，改动会让动作静默读不到

    /** `exec_exp_select_stage` 读的键 */
    private const val STAGE_KEY_EXP = "exp_stage"

    /** `exec_thread_select_stage` 读的键 */
    private const val STAGE_KEY_THREAD = "thread_stage"

    private const val MODE_KEY = "luxcavation_mode"
    private const val MODE_ENTER = "enter"

    /** 注意是带空格的 "skip battle"，不是下划线 */
    private const val MODE_SKIP = "skip battle"

    private const val MIRROR_MODE_KEY = "mirror_mode"
    private const val MIRROR_NORMAL = "normal"
    private const val MIRROR_HARD = "hard"
    private const val ACCEPT_REWARD_KEY = "accept_reward"

    /** 上游 exp_cfg.json 的默认关卡 */
    private const val DEFAULT_STAGE = "07"
}
