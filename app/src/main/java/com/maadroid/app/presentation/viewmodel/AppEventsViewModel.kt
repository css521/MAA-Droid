package com.maadroid.app.presentation.viewmodel

import androidx.lifecycle.ViewModel
import com.maadroid.app.R
import com.maadroid.app.data.achievement.AchievementField
import com.maadroid.app.data.achievement.AchievementRepository
import com.maadroid.app.data.achievement.achievementStringResId
import com.maadroid.app.data.preferences.AppSettingsManager
import com.maadroid.app.presentation.state.UiEffect
import com.maadroid.app.common.i18n.uiTextOf
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
