package com.aliothmoon.maadroid.koin

import com.aliothmoon.maadroid.presentation.viewmodel.AchievementViewModel
import com.aliothmoon.maadroid.presentation.viewmodel.AppEventsViewModel
import com.aliothmoon.maadroid.presentation.viewmodel.BackgroundTaskViewModel
import com.aliothmoon.maadroid.presentation.viewmodel.CopilotViewModel
import com.aliothmoon.maadroid.presentation.viewmodel.ErrorLogViewModel
import com.aliothmoon.maadroid.presentation.viewmodel.ExpandedControlPanelViewModel
import com.aliothmoon.maadroid.presentation.viewmodel.HomeViewModel
import com.aliothmoon.maadroid.presentation.viewmodel.LogHistoryViewModel
import com.aliothmoon.maadroid.presentation.viewmodel.NotificationSettingsViewModel
import com.aliothmoon.maadroid.presentation.viewmodel.SettingsViewModel
import com.aliothmoon.maadroid.presentation.viewmodel.TaskOverrideEditorViewModel
import com.aliothmoon.maadroid.presentation.viewmodel.ToolboxViewModel
import com.aliothmoon.maadroid.presentation.viewmodel.UpdateViewModel
import com.aliothmoon.maadroid.schedule.ui.ScheduleEditViewModel
import com.aliothmoon.maadroid.schedule.ui.ScheduleListViewModel
import com.aliothmoon.maadroid.schedule.ui.ScheduleTriggerLogViewModel
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module


val viewModelModule = module {
    viewModelOf(::HomeViewModel)
    viewModelOf(::AchievementViewModel)
    viewModelOf(::AppEventsViewModel)
    viewModelOf(::SettingsViewModel)
    viewModelOf(::UpdateViewModel)
    viewModelOf(::LogHistoryViewModel)
    viewModelOf(::ErrorLogViewModel)
    viewModelOf(::BackgroundTaskViewModel)
    viewModelOf(::ScheduleListViewModel)
    viewModelOf(::ScheduleEditViewModel)
    viewModelOf(::ScheduleTriggerLogViewModel)
    viewModelOf(::NotificationSettingsViewModel)
    viewModelOf(::TaskOverrideEditorViewModel)
}


val floatingWindowModule = module {
    singleOf(::ExpandedControlPanelViewModel)
    singleOf(::CopilotViewModel)
    singleOf(::ToolboxViewModel)
}
