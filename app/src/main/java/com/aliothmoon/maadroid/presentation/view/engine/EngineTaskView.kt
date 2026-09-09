package com.aliothmoon.maadroid.presentation.view.engine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.aliothmoon.maadroid.constant.DefaultDisplayConfig
import com.aliothmoon.maadroid.engine.DisplaySpec
import com.aliothmoon.maadroid.engine.EngineRegistry
import com.aliothmoon.maadroid.engine.EngineSession
import com.aliothmoon.maadroid.engine.EngineTaskStore
import com.aliothmoon.maadroid.manager.RemoteServiceManager
import com.aliothmoon.maadroid.presentation.viewmodel.EngineTaskViewModel
import com.aliothmoon.maadroid.presentation.components.LogExportController
import com.aliothmoon.maadroid.presentation.state.EngineTaskExecutionState
import com.aliothmoon.maadroid.presentation.pip.LocalIsInPip
import com.aliothmoon.maadroid.ui.asString
import com.aliothmoon.maadroid.remote.EngineDataRoot
import com.aliothmoon.maadroid.engine.resource.EngineResourceService
import com.aliothmoon.maadroid.data.preferences.AppSettingsManager
import org.koin.compose.koinInject

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
    isActivePage: Boolean = true,
) {
    val profile = EngineRegistry.provider(engineId)?.profile
    val chromeHidden = LocalIsInPip.current ||
        LocalEnginePreviewNavigation.current?.fullscreenEngineId == engineId

    Scaffold(
        topBar = {
            if (!chromeHidden) TopAppBar(
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
            modifier = if (chromeHidden) Modifier else Modifier.padding(padding),
            isActivePage = isActivePage,
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
    isActivePage: Boolean = true,
) {
    key(engineId) {
        val context = LocalContext.current.applicationContext
        val resourceService = koinInject<EngineResourceService>()
        val settings = koinInject<AppSettingsManager>()
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
                        resources = resourceService,
                        runMode = settings.runMode.value,
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
        val workspaceDraft by viewModel.workspaceDraft.collectAsStateWithLifecycle()
        val logs by viewModel.logs.collectAsStateWithLifecycle()
        val previewReady by viewModel.previewReady.collectAsStateWithLifecycle()
        val pipOnHome by settings.pipOnHome.collectAsStateWithLifecycle()
        val profile = EngineRegistry.provider(engineId)?.profile
        val workspace = viewModel.workspace
        // Start idle workspaces with room to configure teams/packs; later task state changes
        // must not override the user's choice. The surrounding game key also isolates restoration.
        var previewExpanded by rememberSaveable(engineId) {
            mutableStateOf(workspace == null || running)
        }
        var showLogExport by rememberSaveable { mutableStateOf(false) }
        // Keep the document launcher registered even while the sheet is closed or the task is idle.
        LogExportController(
            sheetVisible = showLogExport && !LocalIsInPip.current &&
                LocalEnginePreviewNavigation.current?.fullscreenEngineId != engineId,
            onSheetDismiss = { showLogExport = false },
        )

        EnginePreviewHost(
            engineId = engineId,
            viewModel = viewModel,
            display = profile?.display ?: DisplaySpec(DefaultDisplayConfig.WIDTH, DefaultDisplayConfig.HEIGHT),
            previewReady = previewReady,
            running = running,
            stopping = stopping,
            expanded = previewExpanded,
            isActivePage = isActivePage,
            pipOnHome = pipOnHome,
            modifier = modifier,
        ) { preview, enterFullscreen ->
            if ((hostTaskActive && !running) || (activeEngineId != null && activeEngineId != engineId)) {
                EngineTaskBlockedContent()
            } else if (viewModel.panels.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.engine_no_task_panels))
                }
            } else {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val resourceDetailsMaxHeight = maxHeight * 0.2f
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Give nested workspace lists a finite viewport after measuring the footer.
                        // Collapsing the preview returns its entire weight to the configuration area.
                        Column(Modifier.fillMaxWidth().weight(1f)) {
                            profile?.display?.let {
                                EnginePreviewControls(
                                    expanded = previewExpanded,
                                    isRunning = running,
                                    onToggleExpanded = { previewExpanded = !previewExpanded },
                                    canEnterFullscreen = previewReady && !stopping && isActivePage,
                                    onEnterFullscreen = enterFullscreen,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                )
                                if (previewExpanded) {
                                    // Full available width, 16:9, with the same 16dp margins as Arknights.
                                    preview()
                                }
                            }
                            if (workspace != null) {
                                Box(Modifier.fillMaxWidth().weight(5f)) {
                                    workspace.Content(
                                        configJson = workspaceDraft ?: tasks.workspaceConfig ?: workspace.initialConfig(tasks.enabled, tasks.params),
                                        onConfigChange = viewModel::onWorkspaceChange,
                                        editable = !running,
                                        logs = logs,
                                        resourceDir = profile?.resourcePacks?.firstOrNull()?.let { EngineDataRoot.forPack(context, it) },
                                    )
                                }
                            } else EngineTaskList(
                                panels = viewModel.panels,
                                enabledOf = { tasks.enabled[it.taskType] ?: it.enabledByDefault },
                                paramsOf = { tasks.params[it.taskType] ?: "" },
                                onEnabledChange = viewModel::onEnabledChange,
                                onParamsChange = viewModel::onParamsChange,
                                expandedTaskType = expanded,
                                onToggleExpand = viewModel::onToggleExpand,
                                modifier = Modifier.weight(5f),
                            )
                        }

                        // Available after process restart, even when in-memory engine logs are empty.
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = status?.asString() ?: stringResource(R.string.engine_log_export_hint),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f).heightIn(max = 64.dp)
                                    .verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
                            )
                            TextButton(onClick = { showLogExport = true }) {
                                Text(stringResource(R.string.settings_log_export_chooser_title))
                            }
                        }

                        val downloadablePacks = profile?.resourcePacks.orEmpty()
                            .filter { it.upstreamArchive != null }
                        if (downloadablePacks.isNotEmpty()) {
                            // Long download/error details scroll locally, keeping task actions visible.
                            Column(
                                Modifier.fillMaxWidth().heightIn(max = resourceDetailsMaxHeight)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                downloadablePacks.forEach { pack ->
                                    EngineResourceCard(pack, resourceService, running)
                                }
                            }
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
