package com.aliothmoon.maadroid.data.model

import com.aliothmoon.maadroid.data.repository.DepotRepository
import com.aliothmoon.maadroid.data.repository.OperBoxRepository
import com.aliothmoon.maadroid.data.resource.ActivityManager
import com.aliothmoon.maadroid.data.resource.ItemHelper
import com.aliothmoon.maadroid.data.resource.ResourceDataManager
import com.aliothmoon.maadroid.domain.models.ReportOptions
import com.aliothmoon.maadroid.domain.service.FightDropsRefresher
import com.aliothmoon.maadroid.utils.i18n.UiText

/**
 * 展开环境：只读世界状态 + 本趟 [appendLog] / [FightDropsRefresher.stage]。
 * 非值对象；配置类不得反向抓依赖。
 */
class TaskParamContext(
    val node: TaskChainNode,
    val clientType: String,
    val chainAllowsCreditFight: Boolean,
    val activityManager: ActivityManager,
    val depotRepository: DepotRepository,
    val operBoxRepository: OperBoxRepository,
    val itemHelper: ItemHelper,
    val resourceDataManager: ResourceDataManager,
    val dropsRefresher: FightDropsRefresher,
    val logSink: PreflightLogSink,
    val report: ReportOptions = ReportOptions.DEFAULT,
    /** App 侧绝对路径映射到 core 读的路径（独立目录模式），见 MaaPathConfig.toCorePath */
    val relocatePath: (String) -> String = { it },
) {
    fun appendLog(text: UiText, level: LogLevel = LogLevel.INFO) {
        logSink.append(text, level)
    }
}
