package com.aliothmoon.maadroid.presentation.view.engine

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
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
import com.aliothmoon.maadroid.engine.EngineResources
import com.aliothmoon.maadroid.engine.EngineSession
import com.aliothmoon.maadroid.engine.EngineTaskStore
import com.aliothmoon.maadroid.engine.ResourcePackSpec
import com.aliothmoon.maadroid.manager.RemoteServiceManager
import com.aliothmoon.maadroid.presentation.viewmodel.EngineTaskViewModel
import com.aliothmoon.maadroid.presentation.viewmodel.EngineTaskQuickActions
import com.aliothmoon.maadroid.domain.service.GameMuteCoordinator
import com.aliothmoon.maadroid.overlay.screensaver.ScreenSaverOverlayManager
import com.aliothmoon.maadroid.presentation.components.TaskQuickActionsButton
import com.aliothmoon.maadroid.presentation.components.TaskQuickActionsOverlay
import com.aliothmoon.maadroid.ui.components.AdaptiveTaskPromptDialog
import com.aliothmoon.maadroid.presentation.components.LogExportController
import com.aliothmoon.maadroid.presentation.state.EngineTaskExecutionState
import com.aliothmoon.maadroid.presentation.pip.LocalIsInPip
import com.aliothmoon.maadroid.remote.EngineDataRoot
import com.aliothmoon.maadroid.engine.resource.EngineResourceService
import com.aliothmoon.maadroid.data.preferences.AppSettingsManager
import com.aliothmoon.maadroid.ui.components.TaskPrimaryButton
import com.aliothmoon.maadroid.ui.components.TaskSecondaryButton
import org.koin.compose.koinInject
import kotlinx.coroutines.launch
import java.io.File

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
        val taskStore = koinInject<EngineTaskStore>()
        val settings = koinInject<AppSettingsManager>()
        val audio = koinInject<GameMuteCoordinator>()
        val screenSaver = koinInject<ScreenSaverOverlayManager>()
        val coroutineScope = rememberCoroutineScope()
        val executionState = engineTaskExecutionState(viewModelStoreOwner)
        val activeEngineId by executionState.activeEngineId.collectAsStateWithLifecycle()
        val viewModel = viewModel<EngineTaskViewModel>(
            viewModelStoreOwner = viewModelStoreOwner,
            key = "engine_task:$engineId",
        ) {
            EngineTaskViewModel(
                engineId = engineId,
                store = taskStore,
                executionState = executionState,
                canStart = canStart,
                quickActions = EngineTaskQuickActions(settings, audio),
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
        val diagnosticFailure by viewModel.diagnosticFailure.collectAsStateWithLifecycle()
        val workspaceDraft by viewModel.workspaceDraft.collectAsStateWithLifecycle()
        val logs by viewModel.logs.collectAsStateWithLifecycle()
        val previewReady by viewModel.previewReady.collectAsStateWithLifecycle()
        val mutedPackage by viewModel.mutedGamePackage.collectAsStateWithLifecycle()
        val pipOnHome by settings.pipOnHome.collectAsStateWithLifecycle()
        val profile = EngineRegistry.provider(engineId)?.profile
        val workspace = viewModel.workspace
        // Match the task page's visible preview; task changes never override a user's collapse.
        var previewExpanded by rememberSaveable(engineId) {
            mutableStateOf(true)
        }
        var showLogExport by rememberSaveable { mutableStateOf(false) }
        var showMoreActions by rememberSaveable { mutableStateOf(false) }
        var showCloseConfirm by rememberSaveable { mutableStateOf(false) }
        val chromeHidden = LocalIsInPip.current || LocalEnginePreviewNavigation.current?.fullscreenEngineId == engineId
        LaunchedEffect(isActivePage, chromeHidden, showCloseConfirm) {
            if (!isActivePage || chromeHidden || showCloseConfirm) showMoreActions = false
        }
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
            } else if (profile == null || (workspace == null && viewModel.panels.isEmpty())) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.engine_no_task_panels))
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Give nested workspace lists a finite viewport after measuring the footer.
                        // Collapsing the preview returns its entire weight to the configuration area.
                        Column(Modifier.fillMaxWidth().weight(1f)) {
                            profile?.display?.let {
                                if (previewExpanded) {
                                    Box(Modifier.fillMaxWidth()) {
                                        preview()
                                        EnginePreviewControls(
                                            expanded = true,
                                            isRunning = running,
                                            onToggleExpanded = { previewExpanded = false },
                                            canEnterFullscreen = previewReady && !stopping && isActivePage,
                                            onEnterFullscreen = enterFullscreen,
                                            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 16.dp)
                                                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                                        )
                                    }
                                } else EnginePreviewControls(
                                    expanded = false,
                                    isRunning = running,
                                    onToggleExpanded = { previewExpanded = true },
                                    canEnterFullscreen = previewReady && !stopping && isActivePage,
                                    onEnterFullscreen = enterFullscreen,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                )
                            }
                            if (workspace != null) {
                                Box(Modifier.fillMaxWidth().weight(5f)) {
                                    EngineTaskWorkspaceContent(
                                        engineId = engineId,
                                        configJson = workspaceDraft ?: tasks.workspaceConfig ?: workspace.initialConfig(tasks.enabled, tasks.params),
                                        onConfigChange = viewModel::onWorkspaceChange,
                                        editable = !running,
                                        logs = logs,
                                        directoryForPack = { EngineDataRoot.forPack(context, it) },
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

                        EngineTaskMessages(
                            status = status,
                            diagnosticFailure = diagnosticFailure,
                            running = running,
                            isActivePage = isActivePage,
                            onExportLogs = { showLogExport = true },
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (!running) TaskPrimaryButton(
                                onClick = viewModel::start,
                                enabled = !running,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.task_btn_start), maxLines = 1)
                            }
                            else TaskSecondaryButton(
                                onClick = viewModel::stop,
                                enabled = running && !stopping,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            ) {
                                if (stopping) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.error)
                                else Text(stringResource(R.string.task_btn_stop), maxLines = 1)
                            }
                            TaskQuickActionsButton(showMoreActions) { showMoreActions = !showMoreActions }
                        }
                    }
                    BackHandler(enabled = showMoreActions) { showMoreActions = false }
                    if (showMoreActions) TaskQuickActionsOverlay(
                        onDismissRequest = { showMoreActions = false },
                        isGameMuted = mutedPackage.isNotEmpty() && mutedPackage == viewModel.gamePackageName,
                        gameActionsEnabled = previewReady && !stopping,
                        onToggleGameSound = viewModel::onToggleGameSound,
                        onScreenOff = viewModel::onScreenOff,
                        onShowScreenSaver = { coroutineScope.launch { screenSaver.show() } },
                        onCloseApp = {
                            if (running) showCloseConfirm = true
                            else { showMoreActions = false; viewModel.onCloseGame() }
                        },
                        onExportLogs = { showMoreActions = false; showLogExport = true },
                        showTouchPreviewSetting = false,
                        appSettingsManager = settings,
                    )
                    if (showCloseConfirm) AdaptiveTaskPromptDialog(
                        visible = true,
                        title = stringResource(R.string.dialog_close_app_title),
                        message = AnnotatedString(stringResource(R.string.dialog_close_app_message)),
                        onDismissRequest = { showCloseConfirm = false },
                        onConfirm = { showCloseConfirm = false; viewModel.onCloseGame() },
                        confirmText = stringResource(R.string.dialog_close_app_confirm),
                        dismissText = stringResource(R.string.common_cancel),
                        icon = Icons.Filled.Warning,
                        iconTint = MaterialTheme.colorScheme.error,
                        confirmColor = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** Resolve every declared pack without checking installation or blocking editable workspace content. */
@Composable
internal fun EngineTaskWorkspaceContent(
    engineId: String,
    configJson: String,
    onConfigChange: (String) -> Unit,
    editable: Boolean,
    logs: List<String>,
    directoryForPack: (ResourcePackSpec) -> File,
) {
    val provider = EngineRegistry.provider(engineId) ?: return
    val workspace = provider.ui.workspace ?: return
    val profile = provider.profile
    workspace.Content(
        configJson = configJson,
        onConfigChange = onConfigChange,
        editable = editable,
        logs = logs,
        resources = EngineResources(profile, profile.resourcePacks.associate { pack ->
            pack.packId to directoryForPack(pack)
        }),
    )
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
