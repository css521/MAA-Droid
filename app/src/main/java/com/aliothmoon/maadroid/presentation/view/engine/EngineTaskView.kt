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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.aliothmoon.maadroid.R
import com.aliothmoon.maadroid.engine.EngineRegistry
import com.aliothmoon.maadroid.engine.EngineSession
import com.aliothmoon.maadroid.engine.EngineTaskStore
import com.aliothmoon.maadroid.manager.RemoteServiceManager
import com.aliothmoon.maadroid.presentation.viewmodel.EngineTaskViewModel

/**
 * 按引擎声明渲染的任务页。
 *
 * **不认识任何具体引擎** —— 面板、参数结构、任务标识全部来自 [EngineRegistry]。
 * 方舟现有的 `BackgroundTaskView` 与它并存；等方舟收拢为 `AutomationEngine`
 * 且面板迁到 `EngineUi.taskPanels` 后，两者合并。
 *
 * 眼下的主要用途是**让边狱引擎在真机上可达**：在此之前 `EngineTaskList` 与
 * `EngineTaskViewModel` 都已写好却没有任何导航入口，等于测不到。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun EngineTaskView(navController: NavController, engineId: String) {
    val context = LocalContext.current
    val store = remember { EngineTaskStore(context) }
    val viewModel = remember(engineId) {
        EngineTaskViewModel(
            engineId = engineId,
            store = store,
            sessionFactory = { id ->
                EngineSession(
                    context = context,
                    engineId = id,
                    // 提权进程的连接由宿主统一管理；引擎只在回调里拿到 RemoteService
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
    val status by viewModel.status.collectAsStateWithLifecycle()
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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
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
                    text = it,
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
                    enabled = running,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.engine_stop))
                }
            }
        }
    }
}
