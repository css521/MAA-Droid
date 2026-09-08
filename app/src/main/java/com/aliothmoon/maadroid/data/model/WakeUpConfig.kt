package com.aliothmoon.maadroid.data.model

import com.aliothmoon.maadroid.data.model.WakeUpConfig.Companion.ACCOUNT_SWITCH_CLIENT_TYPES
import com.aliothmoon.maadroid.maa.task.MaaTaskParams
import com.aliothmoon.maadroid.maa.task.MaaTaskType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 开始唤醒配置
 *
 * MaaCore JSON 参数:
 * - client_type: 客户端类型字符串
 * - start_game_enabled: 是否启动游戏
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
@SerialName("com.aliothmoon.maadroid.data.model.WakeUpConfig")
data class WakeUpConfig(
    /**
     * 客户端类型
     * 对应 WPF: ClientType
     * MaaCore JSON: client_type
     *
     * 选项：
     * - "Official": 官服
     * - "Bilibili": B服
     * - "YoStarEN": 国际服(YoStarEN)
     * - "YoStarJP": 日服(YoStarJP)
     * - "YoStarKR": 韩服(YoStarKR)
     * - "txwy": 繁中服(txwy)
     */
    val clientType: String = "Official",

    /**
     * 是否启用启动游戏
     * 对应 WPF: StartGame
     * MaaCore JSON: start_game_enabled
     */
    val startGameEnabled: Boolean = true,

    /**
     * 账号切换目标
     * 对应 WPF: AccountName
     * MaaCore JSON: account_name
     *
     * 仅 [ACCOUNT_SWITCH_CLIENT_TYPES] 内的客户端生效，其他服将忽略该字段
     */
    val accountName: String = ""
) : TaskParamProvider {
    companion object {
        /**
         * 客户端类型选项值列表
         */
        val CLIENT_TYPES = listOf(
            "Official",
            "Bilibili",
            "YoStarEN",
            "YoStarJP",
            "YoStarKR",
            "txwy"
        )

        /**
         * 支持账号切换的客户端，对齐 Core AccountSwitchTask::SupportedClientType
         * YoStarEN / YoStarJP 仍未支持
         */
        val ACCOUNT_SWITCH_CLIENT_TYPES = setOf("Official", "Bilibili", "txwy", "YoStarKR")

        /**
         * 客户端类型到服务器类型的映射
         * 用于资源更新等逻辑
         */
        fun getServerType(clientType: String): String = when (clientType) {
            "Official", "Bilibili", "" -> "CN"
            "YoStarEN" -> "US"
            "YoStarJP" -> "JP"
            "YoStarKR" -> "KR"
            "txwy" -> "ZH_TW"
            else -> "CN"
        }
    }

    /**
     * 获取服务器类型
     */
    fun getServerType(): String = getServerType(clientType)
    override fun toTaskParams(ctx: TaskParamContext): List<MaaTaskParams> {
        val normalizedAccountName = accountName.trim()
        val canSwitchAccount = clientType in ACCOUNT_SWITCH_CLIENT_TYPES
        val paramsJson = buildJsonObject {
            put("client_type", clientType)
            put("start_game_enabled", startGameEnabled)
            if (canSwitchAccount && normalizedAccountName.isNotEmpty()) {
                put("account_name", normalizedAccountName)
            }
        }
        return listOf(MaaTaskParams(MaaTaskType.START_UP, paramsJson.toString()))
    }
}
