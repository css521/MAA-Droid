package com.aliothmoon.maadroid.data.model

import com.aliothmoon.maadroid.maa.task.MaaTaskParams
import com.aliothmoon.maadroid.maa.task.MaaTaskType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 领取奖励配置
 */
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
@SerialName("com.aliothmoon.maadroid.data.model.AwardConfig")
data class AwardConfig(
    val award: Boolean = true,  // 领取每日/每周任务奖励
    val mail: Boolean = false,  // 领取所有邮件奖励
    val freeGacha: Boolean = false,  // 进行每日免费单抽
    val orundum: Boolean = false,  // 领取幸运墙合成玉
    val mining: Boolean = false,  // 领取挖矿合成玉
    val specialAccess: Boolean = false  // 领取周年特殊月卡
) : TaskParamProvider {
    override fun toTaskParams(ctx: TaskParamContext): List<MaaTaskParams> {
        val paramsJson = buildJsonObject {
            put("award", award)
            put("mail", mail)
            put("recruit", freeGacha)
            put("orundum", orundum)
            put("mining", mining)
            put("specialaccess", specialAccess)
        }
        return listOf(MaaTaskParams(MaaTaskType.AWARD, paramsJson.toString()))
    }
}