package com.aliothmoon.maadroid.presentation.viewmodel

import androidx.lifecycle.ViewModel
import com.aliothmoon.maadroid.R
import com.aliothmoon.maadroid.data.achievement.AchievementField
import com.aliothmoon.maadroid.data.achievement.AchievementRepository
import com.aliothmoon.maadroid.data.achievement.achievementStringResId
import com.aliothmoon.maadroid.data.preferences.AppSettingsManager
import com.aliothmoon.maadroid.presentation.state.UiEffect
import com.aliothmoon.maadroid.utils.i18n.uiTextOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull


class AppEventsViewModel(
    achievementRepository: AchievementRepository,
    private val appSettings: AppSettingsManager,
) : ViewModel() {

    val effects: Flow<UiEffect> = achievementRepository.unlockEvents
        .filter { appSettings.showAchievementSnackbar.value }
        .mapNotNull {
            val resId = achievementStringResId(it, AchievementField.TITLE)
            if (resId != 0) {
                UiEffect.toast(R.string.achievement_unlocked_message, uiTextOf(resId))
            } else {
                null
            }
        }

}
