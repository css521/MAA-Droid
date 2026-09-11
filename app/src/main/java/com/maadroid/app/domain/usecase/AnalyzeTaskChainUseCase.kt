package com.maadroid.app.domain.usecase

import com.maadroid.app.constant.Packages
import com.maadroid.app.data.model.CollectingPreflightLogSink
import com.maadroid.app.data.model.FightConfig
import com.maadroid.app.data.model.LogLevel
import com.maadroid.app.data.model.TaskChainNode
import com.maadroid.app.data.model.TaskParamContext
import com.maadroid.app.data.model.WakeUpConfig
import com.maadroid.app.data.preferences.AppSettingsManager
import com.maadroid.app.data.preferences.TaskChainState
import com.maadroid.app.data.repository.DepotRepository
import com.maadroid.app.data.repository.OperBoxRepository
import com.maadroid.app.data.resource.ActivityManager
import com.maadroid.app.data.resource.ItemHelper
import com.maadroid.app.data.resource.ResourceDataManager
import com.maadroid.app.data.resource.ServerTimezone
import com.maadroid.app.domain.models.MallCreditFightAvailability
import com.maadroid.app.domain.models.ReportOptions
import com.maadroid.app.domain.service.FightDropsRefresher
import com.maadroid.app.maa.task.MaaTaskParams
import com.maadroid.app.maa.task.MaaTaskType
import com.maadroid.app.maa.task.TaskSlot
import com.maadroid.app.common.i18n.UiText
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.time.DayOfWeek

class AnalyzeTaskChainUseCase(
    private val taskChainState: TaskChainState,
    private val resourceDataManager: ResourceDataManager,
    private val activityManager: ActivityManager,
    private val depotRepository: DepotRepository,
    private val operBoxRepository: OperBoxRepository,
    private val itemHelper: ItemHelper,
    private val dropsRefresher: FightDropsRefresher,
    private val appSettingsManager: AppSettingsManager,
    private val relocatePath: (String) -> String = { it },
) {
    /** 先等 depot/operBox 分片装载；config 的 toTaskParams 仍是非 suspend。 */
    suspend operator fun invoke(chain: List<TaskChainNode>): AnalyzeTaskChainResult {
        depotRepository.isLoaded.first { it }
        operBoxRepository.isLoaded.first { it }

        val nodes = chain.filter { it.enabled }.sortedBy { it.order }
        if (nodes.isEmpty()) {
            return AnalyzeTaskChainResult.Blocked(
                reason = AnalyzeTaskChainFailureReason.NO_TASK_SELECTED,
            )
        }
        val list = getWakeUpClientTypeList(nodes)
        if (list.size > 1) {
            return AnalyzeTaskChainResult.Blocked(
                reason = AnalyzeTaskChainFailureReason.CONFLICTING_CLIENT_TYPES,
                clientTypes = list,
            )
        }


        val info = MallCreditFightAvailability.resolve(nodes, activityManager)

        dropsRefresher.clear()

        val clientType = taskChainState.clientType
        val report = ReportOptions.of(
            clientType = clientType,
            reportToPenguin = appSettingsManager.reportToPenguin.value,
            reportToYituliu = appSettingsManager.reportToYituliu.value,
            penguinId = appSettingsManager.penguinId.value,
        )
        val log = CollectingPreflightLogSink()

        val serverDayOfWeek = ServerTimezone.getYjDayOfWeek(clientType)
        val expanded = nodes.flatMap { node ->
            if (isSkippedByWeeklySchedule(node, serverDayOfWeek)) {
                return@flatMap emptyList()
            }
            val ctx = TaskParamContext(
                node = node,
                clientType = clientType,
                chainAllowsCreditFight = info.isAvailable,
                itemHelper = itemHelper,
                activityManager = activityManager,
                depotRepository = depotRepository,
                operBoxRepository = operBoxRepository,
                resourceDataManager = resourceDataManager,
                dropsRefresher = dropsRefresher,
                logSink = log,
                report = report,
                relocatePath = relocatePath,
            )
            node.config.toTaskParams(ctx).mapIndexed { index, task ->
                task.copy(slot = TaskSlot(node.id, index))
            }
        }
        val params = dropAdjacentDuplicateDepot(expanded)
        val logs = log.entries

        if (params.isEmpty()) {
            return AnalyzeTaskChainResult.Blocked(
                reason = AnalyzeTaskChainFailureReason.NO_EXECUTABLE_TASKS,
                logs = logs,
            )
        }

        return AnalyzeTaskChainResult.Ready(
            TaskChainPlan(
                nodes = nodes,
                params = params,
                clientType = clientType,
                gamePackageName = Packages[clientType],
                launchesGame = nodes
                    .mapNotNull { it.config as? WakeUpConfig }
                    .any { it.startGameEnabled },
                logs = logs,
            )
        )
    }

    /** 去掉相邻重复 DEPOT；中间有其它任务则保留（库存可能已变）。 */
    private fun dropAdjacentDuplicateDepot(params: List<MaaTaskParams>): List<MaaTaskParams> =
        params.filterIndexed { index, task ->
            index == 0 ||
                    task.type != MaaTaskType.DEPOT ||
                    params[index - 1].type != MaaTaskType.DEPOT
        }

    private fun getWakeUpClientTypeList(nodes: List<TaskChainNode>): List<String> {
        return nodes.mapNotNull { (it.config as? WakeUpConfig)?.clientType }
            .distinct()
    }

    private fun isSkippedByWeeklySchedule(
        node: TaskChainNode,
        serverDayOfWeek: DayOfWeek
    ): Boolean {
        val config = node.config
        if (config is FightConfig && config.useWeeklySchedule) {
            if (config.weeklySchedule[serverDayOfWeek.name] == false) {
                Timber.d("WeeklySchedule: skip node '%s' on %s", node.name, serverDayOfWeek)
                return true
            }
        }
        return false
    }


}

data class TaskChainPlan(
    val nodes: List<TaskChainNode>,
    val params: List<MaaTaskParams>,
    val clientType: String,
    val gamePackageName: String?,
    val launchesGame: Boolean,
    val gameAliveBeforeStart: Boolean? = null,
    /** 预检日志，会话开始后由 Composition 回放。 */
    val logs: List<Pair<UiText, LogLevel>> = emptyList(),
)

enum class AnalyzeTaskChainFailureReason {
    NO_TASK_SELECTED,
    CONFLICTING_CLIENT_TYPES,
    NO_EXECUTABLE_TASKS,
}

sealed interface AnalyzeTaskChainResult {
    data class Ready(val plan: TaskChainPlan) : AnalyzeTaskChainResult

    data class Blocked(
        val reason: AnalyzeTaskChainFailureReason,
        val clientTypes: List<String> = emptyList(),
        val logs: List<Pair<UiText, LogLevel>> = emptyList(),
    ) : AnalyzeTaskChainResult
}
