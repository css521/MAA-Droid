package com.aliothmoon.maadroid.engine.arknights

import java.io.File

/** 本轮资源档与连接选项；资源适配器保证 native 可读资源已经就位。 */
data class MaaRunOptions(val clientType: String, val deployWithPause: Boolean)

/**
 * 宿主已有的安装/提权投递适配点。资源档切换必须在创建设备会话之前完成，
 * 因为 MaaCore 全局资源不可回滚，切换渠道可能需要重启提权进程。
 */
fun interface MaaResourcePreparation {
    suspend fun prepare(resourceDir: File, options: MaaRunOptions): Result<Unit>
}
