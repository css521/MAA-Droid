package com.aliothmoon.maadroid.presentation.view.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aliothmoon.maadroid.data.achievement.AchievementField
import com.aliothmoon.maadroid.data.achievement.achievementStringResId

@Composable
fun achievementText(id: String, field: AchievementField): String {
    val resId = achievementStringResId(id, field)
    return if (resId == 0) "" else stringResource(resId)
}
