package com.aliothmoon.maadroid.presentation.view.engine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.aliothmoon.maadroid.R
import com.aliothmoon.maadroid.engine.EngineRegistry
import com.aliothmoon.maadroid.engine.EngineSession
import com.aliothmoon.maadroid.engine.EngineTaskStore
import com.aliothmoon.maadroid.manager.RemoteServiceManager
import com.aliothmoon.maadroid.presentation.viewmodel.EngineTaskViewModel
import com.aliothmoon.maadroid.presentation.state.EngineTaskExecutionState
import com.aliothmoon.maadroid.ui.asString

/**
 * 按引擎声明渲染的任务页。
 *
 * 面板、参数结构、任务标识来自 [EngineRegistry]；方舟仍保留原有任务页。
 * 独立路由保留返回栏；后台游戏 tabs 直接嵌入 [EngineTaskContent]，避免双 Scaffold。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun EngineTaskView(
    navController: NavController,
    engineId: String,
    hostTaskActive: Boolean,
    canStart: () -> Boolean,
    viewModelStoreOwner: ViewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current),
) {
    val profile = EngineRegistry.provider(engineId)?.profile

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(profile?.displayNameRes?.let { stringResource(it) } ?: engineId)
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        EngineTaskContent(
            engineId = engineId,
            hostTaskActive = hostTaskActive,
            canStart = canStart,
            modifier = Modifier.padding(padding),
            viewModelStoreOwner = viewModelStoreOwner,
        )
    }
}

/** 与独立路由共享按 engineId 缓存的 VM；离开组合不会结束运行中的任务。 */
@Composable
fun EngineTaskContent(
    engineId: String,
    hostTaskActive: Boolean,
    canStart: () -> Boolean,
    modifier: Modifier = Modifier,
    viewModelStoreOwner: ViewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current),
) {
    key(engineId) {
        val context = LocalContext.current.applicationContext
        val executionState = engineTaskExecutionState(viewModelStoreOwner)
        val activeEngineId by executionState.activeEngineId.collectAsStateWithLifecycle()
        val viewModel = viewModel<EngineTaskViewModel>(
            viewModelStoreOwner = viewModelStoreOwner,
            key = "engine_task:$engineId",
        ) {
            EngineTaskViewModel(
                engineId = engineId,
                store = EngineTaskStore(context),
                executionState = executionState,
                canStart = canStart,
                sessionFactory = { id ->
                    EngineSession(
                        context = context,
                        engineId = id,
                        serviceProvider = { block ->
                            RemoteServiceManager.useRemoteService { service -> block(service) }
                        },
                    )
                },
            )
        }
        val tasks by viewModel.tasks.collectAsStateWithLifecycle()
        val expanded by viewModel.expandedTaskType.collectAsStateWithLifecycle()
        val running by viewModel.running.collectAsStateWithLifecycle()
        val stopping by viewModel.stopping.collectAsStateWithLifecycle()
        val status by viewModel.status.collectAsStateWithLifecycle()

        if ((hostTaskActive && !running) || (activeEngineId != null && activeEngineId != engineId)) {
            EngineTaskBlockedContent(modifier)
            return@key
        }

        Column(modifier = modifier.fillMaxSize()) {
            if (viewModel.panels.isEmpty()) {
                // 方舟目前就是这条路径：它的面板仍挂在宿主导航里，taskPanels 为空
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.engine_no_task_panels))
                }
                return@Column
            }

            EngineTaskList(
                panels = viewModel.panels,
                enabledOf = { tasks.enabled[it.taskType] ?: it.enabledByDefault },
                paramsOf = { tasks.params[it.taskType] ?: "" },
                onEnabledChange = viewModel::onEnabledChange,
                onParamsChange = viewModel::onParamsChange,
                expandedTaskType = expanded,
                onToggleExpand = viewModel::onToggleExpand,
                modifier = Modifier.weight(1f),
            )

            status?.let {
                Text(
                    text = it.asString(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = viewModel::start,
                    enabled = !running,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.engine_start))
                }
                OutlinedButton(
                    onClick = viewModel::stop,
                    enabled = running && !stopping,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(if (stopping) R.string.engine_stopping else R.string.engine_stop))
                }
            }
        }
    }
}

@Composable
internal fun engineTaskExecutionState(
    owner: ViewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current),
): EngineTaskExecutionState = viewModel(viewModelStoreOwner = owner, key = "engine_task_execution") {
    EngineTaskExecutionState()
}

@Composable
internal fun EngineTaskBlockedContent(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.engine_other_game_running))
    }
}
