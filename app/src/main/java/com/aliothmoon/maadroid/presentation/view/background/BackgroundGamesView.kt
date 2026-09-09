package com.aliothmoon.maadroid.presentation.view.background

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliothmoon.maadroid.R
import com.aliothmoon.maadroid.data.preferences.AppSettingsManager
import com.aliothmoon.maadroid.engine.EngineRegistry
import com.aliothmoon.maadroid.presentation.components.MaaWindowInsets
import com.aliothmoon.maadroid.presentation.pip.LocalIsInPip
import com.aliothmoon.maadroid.presentation.view.engine.EngineTaskContent
import com.aliothmoon.maadroid.presentation.view.engine.LocalEnginePreviewNavigation
import com.aliothmoon.maadroid.presentation.view.engine.EngineTaskBlockedContent
import com.aliothmoon.maadroid.presentation.view.engine.engineTaskExecutionState
import com.aliothmoon.maadroid.presentation.viewmodel.BackgroundTaskViewModel
import com.aliothmoon.maadroid.remote.EngineIds
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** BACKGROUND 主页面：游戏选择只切换 UI，不启动、停止或释放引擎。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundGamesView(
    backgroundTaskViewModel: BackgroundTaskViewModel,
    isActivePage: Boolean,
    fullscreen: Boolean,
    hostTaskActive: Boolean,
    canStartEngineTask: () -> Boolean,
    appSettings: AppSettingsManager = koinInject(),
) {
    val profiles = remember { EngineRegistry.profiles() }
    // StateFlow 的初值已从 DataStore 读就绪；不要用默认方舟页反向覆盖保存的选择。
    val currentGameId by appSettings.currentGameId.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val pageStates = rememberSaveableStateHolder()
    val executionState = engineTaskExecutionState()
    val activeEngineId by executionState.activeEngineId.collectAsStateWithLifecycle()
    val engineFullscreen = LocalEnginePreviewNavigation.current?.fullscreenEngineId
    val chromeHidden = fullscreen || LocalIsInPip.current || engineFullscreen != null
    // Keep the game whose preview entered PiP even if the saved selection changes meanwhile.
    var previewGameId by rememberSaveable { mutableStateOf(currentGameId) }
    if (!chromeHidden) SideEffect { previewGameId = currentGameId }
    val displayedGameId = engineFullscreen ?: when {
        fullscreen -> EngineIds.ARKNIGHTS
        LocalIsInPip.current -> previewGameId
        else -> currentGameId
    }
    val selectedProfile = profiles.firstOrNull { it.id == displayedGameId }
        ?: profiles.firstOrNull()

    if (selectedProfile == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.engine_no_games))
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().then(
            if (chromeHidden) Modifier else Modifier.windowInsetsPadding(MaaWindowInsets.topBar),
        ),
    ) {
        if (!chromeHidden) {
            PrimaryTabRow(selectedTabIndex = profiles.indexOf(selectedProfile)) {
                profiles.forEach { profile ->
                    key(profile.id) {
                        Tab(
                            selected = profile.id == selectedProfile.id,
                            onClick = {
                                if (profile.id != appSettings.currentGameId.value) {
                                    scope.launch { appSettings.setCurrentGameId(profile.id) }
                                }
                            },
                            text = { Text(stringResource(profile.displayNameRes)) },
                        )
                    }
                }
            }
        }

        // 同名任务的面板 remember / 列表滚动状态也必须按游戏隔离。
        Box(Modifier.weight(1f)) {
            key(selectedProfile.id) {
                pageStates.SaveableStateProvider(selectedProfile.id) {
                    if (selectedProfile.id == EngineIds.ARKNIGHTS) {
                        if (activeEngineId != null && !chromeHidden) {
                            EngineTaskBlockedContent()
                        } else {
                            BackgroundTaskView(
                                viewModel = backgroundTaskViewModel,
                                isActivePage = isActivePage,
                            )
                        }
                    } else {
                        EngineTaskContent(
                            engineId = selectedProfile.id,
                            hostTaskActive = hostTaskActive,
                            canStart = canStartEngineTask,
                            isActivePage = isActivePage,
                        )
                    }
                }
            }
        }
    }
}
