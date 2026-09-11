package com.maadroid.app.engine

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable

/**
 * 引擎向宿主暴露的 UI 插槽。
 *
 * 宿主的任务页 / 设置页 / 引导页据此渲染，**不 import 任何引擎的具体面板**。
 * 边狱通过 [workspace] 提供跨任务配置页；简易引擎可只实现 [taskPanels]。
 * 方舟的旧面板目前仍由宿主装配，后续迁入同一契约。
 */
interface EngineUi {

    /** 跨任务共用的队伍、策略、资料页。未提供时宿主仍渲染 taskPanels。 */
    val workspace: EngineWorkspace? get() = null

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

/** 引擎自己解释配置；宿主只负责持久化、运行和提供日志/各资源包目录。 */
interface EngineWorkspace {
    fun initialConfig(enabled: Map<String, Boolean>, taskParams: Map<String, String>): String
    fun selectedTasks(configJson: String): List<Pair<String, String>>
    fun validate(configJson: String): String? = null

    @Composable
    fun Content(
        configJson: String,
        onConfigChange: (String) -> Unit,
        editable: Boolean,
        logs: List<String>,
        resources: EngineResources,
    )
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
