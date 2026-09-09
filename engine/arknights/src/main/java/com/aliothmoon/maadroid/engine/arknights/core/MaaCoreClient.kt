package com.aliothmoon.maadroid.engine.arknights.core

/** MaaCore 会话需要的最小接口；不包含宿主的资源路径、设置或 UI 类型。 */
interface MaaCoreClient {
    fun hasInstance(): Boolean
    fun createInstance(callback: (Int, String?) -> Unit): Boolean
    fun setInstanceOption(key: Int, value: String): Boolean
    fun asyncConnect(config: String): Int
    fun appendTask(type: String, params: String): Int
    fun start(): Boolean
    fun stop(): Boolean
    fun running(): Boolean
    fun version(): String
}
