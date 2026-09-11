package com.maadroid.app.koin

import com.maadroid.app.presentation.viewmodel.AchievementViewModel
import com.maadroid.app.presentation.viewmodel.AppEventsViewModel
import com.maadroid.app.presentation.viewmodel.BackgroundTaskViewModel
import com.maadroid.app.presentation.viewmodel.CopilotViewModel
import com.maadroid.app.presentation.viewmodel.ErrorLogViewModel
import com.maadroid.app.presentation.viewmodel.ExpandedControlPanelViewModel
import com.maadroid.app.presentation.viewmodel.HomeViewModel
import com.maadroid.app.presentation.viewmodel.LogHistoryViewModel
import com.maadroid.app.presentation.viewmodel.NotificationSettingsViewModel
import com.maadroid.app.presentation.viewmodel.SettingsViewModel
import com.maadroid.app.presentation.viewmodel.TaskOverrideEditorViewModel
import com.maadroid.app.presentation.viewmodel.ToolboxViewModel
import com.maadroid.app.presentation.viewmodel.UpdateViewModel
import com.maadroid.app.schedule.ui.ScheduleEditViewModel
import com.maadroid.app.schedule.ui.ScheduleListViewModel
import com.maadroid.app.schedule.ui.ScheduleTriggerLogViewModel
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
