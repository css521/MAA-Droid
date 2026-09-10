package com.aliothmoon.maadroid.data.preferences

import com.aliothmoon.maadroid.data.model.TaskProfile
import com.aliothmoon.maadroid.data.notification.NotificationSettings
import com.aliothmoon.maadroid.domain.models.AppSettings
import com.aliothmoon.maadroid.schedule.model.ScheduleStrategy
import kotlinx.serialization.Serializable

@Serializable
data class ConfigBackup(
    val version: Int = 1,
    val exportedAt: String = "",
    val appSettings: AppSettings,
    val notificationSettings: NotificationSettings,
    val taskProfiles: List<TaskProfile>,
    val activeProfileId: String,
    val scheduleStrategies: List<ScheduleStrategy>,
    /** engineId -> 原始任务状态 JSON；缺省支持读取仅包含方舟的 v1 备份。 */
    val engineTasks: Map<String, String> = emptyMap(),
)
