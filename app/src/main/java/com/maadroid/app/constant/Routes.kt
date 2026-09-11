package com.maadroid.app.constant

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val ACHIEVEMENT = "achievement"
    const val ACHIEVEMENT_DEBUG = "achievement_debug"
    const val LOG_HISTORY = "log_history"
    const val ERROR_LOG = "error_log"
    const val BACKGROUND_TASK = "background_task"
    const val SCHEDULE = "schedule"
    const val SCHEDULE_EDIT = "schedule_edit/{strategyId}"
    const val SCHEDULE_TRIGGER_LOG = "schedule_trigger_log"
    const val NOTIFICATION = "notification"
    const val TASK_OVERRIDE_EDITOR = "task_override_editor"

    /**
     * 按引擎声明渲染的任务页。engineId 见 `EngineIds`。
     *
     * 与 [BACKGROUND_TASK]（方舟专属的任务页）并存：方舟的面板尚未迁到
     * `EngineUi.taskPanels`，两条路径合并要等它收拢为 `AutomationEngine`。
     */
    const val ENGINE_TASK = "engine_task/{engineId}"

    fun engineTask(engineId: String) = "engine_task/$engineId"
}
