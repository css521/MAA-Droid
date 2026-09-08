package com.aliothmoon.maadroid.data.model

import com.aliothmoon.maadroid.data.resource.ServerTimezone
import com.aliothmoon.maadroid.domain.models.UserDataUpdateTriggerInterval
import com.aliothmoon.maadroid.domain.models.isUserDataUpdateDue
import com.aliothmoon.maadroid.maa.task.MaaTaskParams
import com.aliothmoon.maadroid.maa.task.MaaTaskType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 更新数据：按间隔展开为 0~2 个识别任务。 */
/**
 * 判别符固定为**当前**全限定类名。
 *
 * 这串字符不是包引用，而是**已经写进用户设备存档**的值：`TaskChainNode.config` 是
 * sealed 类型，kotlinx 默认用全限定类名做多态判别符。不显式钉住的话，此类一旦随
 * `engine/arknights` 换包，所有已装用户的任务链与配置档都会读不出来（表现为任务链
 * 变空，用户以为配置丢了）。钉住之后类可以自由挪动。
 *
 * 因此**不要**把它改成短名或跟着新包名更新 —— 那等于同样的数据丢失。
 * 契约由 TaskConfigWireFormatTest 钉住。
 */
@Serializable
@SerialName("com.aliothmoon.maadroid.data.model.UserDataUpdateConfig")
data class UserDataUpdateConfig(
    val updateOperBox: Boolean = true,
    val updateDepot: Boolean = true,
    val triggerInterval: UserDataUpdateTriggerInterval = UserDataUpdateTriggerInterval.EVERY_TIME,
) : TaskParamProvider {

    override fun toTaskParams(ctx: TaskParamContext): List<MaaTaskParams> {
        if (!updateOperBox && !updateDepot) {
            return emptyList()
        }

        val yjToday = ServerTimezone.getYjDate(ctx.clientType)
        val yjZone = ServerTimezone.getServerZone(ctx.clientType)
        val operDue = updateOperBox && isUserDataUpdateDue(
            lastSyncMillis = ctx.operBoxRepository.snapshot.value.syncTimeMillis,
            interval = triggerInterval,
            yjToday = yjToday,
            yjZone = yjZone,
        )
        val depotDue = updateDepot && isUserDataUpdateDue(
            lastSyncMillis = ctx.depotRepository.snapshot.value.syncTimeMillis,
            interval = triggerInterval,
            yjToday = yjToday,
            yjZone = yjZone,
        )
        if (!operDue && !depotDue) {
            return emptyList()
        }

        // 对齐上游：先干员后仓库（串行）。
        return buildList {
            if (operDue) add(MaaTaskParams(MaaTaskType.OPER_BOX, "{}"))
            if (depotDue) add(MaaTaskParams(MaaTaskType.DEPOT, "{}"))
        }
    }
}
