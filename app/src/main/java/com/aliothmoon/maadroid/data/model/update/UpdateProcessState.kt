package com.aliothmoon.maadroid.data.model.update

import com.aliothmoon.maadroid.data.datasource.ByteProgress

/**
 * 下载/安装过程状态
 */
sealed class UpdateProcessState {
    data object Idle : UpdateProcessState()
    data class Downloading(
        val progress: Int,
        val speed: String,
        val downloaded: Long,
        val total: Long
    ) : UpdateProcessState() {
        val bytes: ByteProgress get() = ByteProgress(downloaded, total)
    }

    data object Verifying : UpdateProcessState()

    data class Extracting(
        val progress: Int,
        val current: Int,
        val total: Int
    ) : UpdateProcessState()

    data object Installing : UpdateProcessState()
    data object Success : UpdateProcessState()
    data class Failed(val error: UpdateError) : UpdateProcessState()
}
