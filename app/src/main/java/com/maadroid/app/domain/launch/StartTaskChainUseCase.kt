package com.maadroid.app.domain.launch

import android.content.Context
import com.maadroid.app.R
import com.maadroid.app.data.model.TaskChainNode
import com.maadroid.app.data.preferences.AppSettingsManager
import com.maadroid.app.domain.service.AchievementReporter
import com.maadroid.app.domain.service.GameMuteCoordinator
import com.maadroid.app.domain.service.MaaCompositionService
import com.maadroid.app.domain.service.MaaSessionLogger
import com.maadroid.app.domain.service.resolveStartResultMessage
import com.maadroid.app.domain.usecase.PrepareTaskStartUseCase
import com.maadroid.app.domain.usecase.TaskStartContext
import com.maadroid.app.domain.usecase.TaskStartDecision
import com.maadroid.app.schedule.model.ExecutionResult
import com.maadroid.app.common.i18n.UiText
import com.maadroid.app.common.i18n.uiTextOf

/**
 * 任务链启动尾部：prepare + mute + composition.start + achievement + 可选 schedule 会话日志
 * 手动与自动化共用；SCHEDULED 不会产生 RequiresConfirmation
 */
class StartTaskChainUseCase(
    private val prepare: PrepareTaskStartUseCase,
    private val composition: MaaCompositionService,
    private val muteCoordinator: GameMuteCoordinator,
    private val achievements: AchievementReporter,
    private val sessionLogger: MaaSessionLogger,
    private val appSettingsManager: AppSettingsManager,
    private val appContext: Context,
) {
    sealed interface Result {
        data object Success : Result
        data class Failed(
            val executionResult: ExecutionResult,
            val message: UiText,
        ) : Result
    }

    suspend operator fun invoke(
        chain: List<TaskChainNode>,
        context: TaskStartContext,
        scheduleLabel: String? = null,
    ): Result {
        val plan = when (val decision = prepare(chain, context)) {
            is TaskStartDecision.Ready -> decision.plan
            is TaskStartDecision.Blocked -> {
                return Result.Failed(
                    executionResult = ExecutionResult.FAILED_VALIDATION,
                    message = uiTextOf(
                        R.string.schedule_log_task_blocked,
                        decision.reason.name,
                    ),
                )
            }

            is TaskStartDecision.RequiresConfirmation -> {
                return Result.Failed(
                    executionResult = ExecutionResult.FAILED_VALIDATION,
                    message = uiTextOf(R.string.schedule_log_task_needs_confirmation),
                )
            }
        }

        // 必须先于静音，换进程会让旧进程收尾时解除静音
        composition.prepareResources(plan.clientType)

        if (appSettingsManager.muteOnGameLaunch.value) {
            muteCoordinator.mute(plan.clientType)
        }

        val startResult = composition.start(
            tasks = plan.params,
            clientType = plan.clientType,
            preflightLogs = plan.logs,
        ) {
            if (scheduleLabel != null) {
                sessionLogger.appendAndWait(
                    appContext.getString(
                        R.string.task_start_triggered_by_schedule,
                        scheduleLabel,
                    ),
                )
            }
        }

        return when (startResult) {
            is MaaCompositionService.StartResult.Success -> {
                achievements.reportTaskStarted(
                    taskCount = plan.params.size,
                    launchesGame = plan.launchesGame,
                    gameAliveBeforeStart = plan.gameAliveBeforeStart,
                )
                Result.Success
            }

            else -> Result.Failed(
                executionResult = ExecutionResult.FAILED_START,
                message = resolveStartResultMessage(startResult)
                    ?: uiTextOf(R.string.task_start_error_start_failed),
            )
        }
    }
}
