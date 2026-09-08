package com.aliothmoon.maadroid.engine

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable

/**
 * 引擎向宿主暴露的 UI 插槽。
 *
 * 宿主的任务页 / 设置页 / 引导页据此渲染，**不 import 任何引擎的具体面板**。
 * 方舟现有的 13k 行任务面板（刷理智、公招、基建、肉鸽…）就是通过 [taskPanels]
 * 挂进来的；边狱将来挂自己的（镜牢、经验本、纽本…）。
 */
interface EngineUi {

    /** 该引擎提供的任务面板，顺序即宿主任务页的呈现顺序 */
    val taskPanels: List<TaskPanelSpec>

    /** 引擎专属设置项，嵌在宿主设置页的一个分组里；无则留空实现 */
    @Composable
    fun SettingsSection() {
    }

    /** 引擎专属引导步骤（如方舟要选服务器、边狱要选游戏语言）；无则留空实现 */
    @Composable
    fun OnboardingSteps() {
    }
}

/**
 * 一个任务的配置面板。
 *
 * @param taskType 与 [AutomationEngine.appendTask] 的 type 对应
 * @param enabledByDefault 首次使用时是否默认勾选
 */
class TaskPanelSpec(
    val taskType: String,
    @StringRes val titleRes: Int,
    val enabledByDefault: Boolean = true,
    /**
     * 面板内容。[onParamsChange] 交回该任务的参数 JSON —— 宿主原样存储、原样传给引擎，
     * 不解释其结构，因此新增任务不需要改宿主。
     */
    val content: @Composable (paramsJson: String, onParamsChange: (String) -> Unit) -> Unit,
)
