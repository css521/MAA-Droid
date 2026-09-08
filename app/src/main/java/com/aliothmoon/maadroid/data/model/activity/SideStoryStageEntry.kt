package com.aliothmoon.maadroid.data.model.activity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 支线活动入口
 */
@Serializable
data class SideStoryStageEntry(
    @SerialName("MinimumRequired")
    val minimumRequired: String? = null,

    @SerialName("Activity")
    val activity: ActivityInfo? = null,

    @SerialName("Stages")
    val stages: List<ActivityStageRaw>? = null
)
