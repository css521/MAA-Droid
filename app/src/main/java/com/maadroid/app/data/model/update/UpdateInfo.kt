package com.maadroid.app.data.model.update

/**
 * 更新信息
 */
data class UpdateInfo(
    val version: String,
    val releaseNote: String? = null,
)