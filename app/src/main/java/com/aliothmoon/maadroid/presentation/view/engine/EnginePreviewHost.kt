package com.aliothmoon.maadroid.presentation.view.engine

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangedIgnoreConsumed
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aliothmoon.maadroid.R
import com.aliothmoon.maadroid.constant.DefaultDisplayConfig
import com.aliothmoon.maadroid.engine.DisplaySpec
import com.aliothmoon.maadroid.presentation.pip.LocalIsInPip
import com.aliothmoon.maadroid.presentation.pip.PipController
import com.aliothmoon.maadroid.presentation.pip.PipHost
import com.aliothmoon.maadroid.presentation.pip.PipRequest
import com.aliothmoon.maadroid.presentation.view.background.VirtualDisplayPreview
import com.aliothmoon.maadroid.presentation.view.background.VirtualDisplayPreviewStatus
import com.aliothmoon.maadroid.presentation.viewmodel.EngineTaskViewModel

/** Shares the host's fullscreen/PiP conventions, while all device IO stays on the engine lease. */
@Composable
internal fun EnginePreviewHost(
    engineId: String,
    viewModel: EngineTaskViewModel,
    display: DisplaySpec,
    previewReady: Boolean,
    running: Boolean,
    stopping: Boolean,
    expanded: Boolean,
    isActivePage: Boolean,
    pipOnHome: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (preview: @Composable () -> Unit, enterFullscreen: () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val pipHost = context as? PipHost
    val inPip = LocalIsInPip.current
    val navigation = LocalEnginePreviewNavigation.current
    val owner = remember { Any() }
    var fullscreen by rememberSaveable(engineId) { mutableStateOf(false) }
    var surfaceAvailable by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val active by rememberUpdatedState(isActivePage)
    val previewContent = remember(viewModel, display) {
        movableContentOf {
            EngineDisplayPreview(
                displayWidth = display.width,
                displayHeight = display.height,
                isActivePage = active,
                onSurfaceAvailable = viewModel::onPreviewSurfaceAvailable,
                onSurfaceDestroyed = viewModel::onPreviewSurfaceDestroyed,
                onSurfaceStateChanged = { surfaceAvailable = it },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
    val showFullscreen = fullscreen && isActivePage && !inPip && previewReady && !stopping
    LaunchedEffect(isActivePage, inPip, previewReady, stopping) {
        if (!isActivePage || inPip || !previewReady || stopping) fullscreen = false
    }
    DisposableEffect(navigation, showFullscreen) {
        if (showFullscreen) navigation?.enterFullscreen(owner, engineId)
        onDispose { navigation?.exitFullscreen(owner) }
    }

    // As in BackgroundTaskView, do not auto-enter with forced landscape/system bars active.
    // A collapsed preview can still enter PiP: compose and attach its Surface on entry.
    val pipEligible = pipOnHome && isActivePage && previewReady && !stopping &&
        !showFullscreen && PipController.isSupported(context)
    val resolution = remember(display) {
        DefaultDisplayConfig.Resolution(display.width, display.height, display.dpi)
    }
    val sourceRect = bounds.takeIf { expanded && !showFullscreen }
    DisposableEffect(pipHost, activity, pipEligible, resolution, sourceRect) {
        val request = PipRequest(resolution, sourceRect)
        if (pipEligible && pipHost != null && activity != null) {
            pipHost.pipRequest = request
            PipController.updateParams(activity, true, request)
        }
        onDispose {
            // An offscreen route or a stale effect must not disarm the visible game's request.
            if (pipHost?.pipRequest === request) {
                pipHost.pipRequest = null
                if (activity != null) PipController.updateParams(activity, false, request)
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        when {
            inPip -> Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Box(Modifier.aspectRatio(display.width.toFloat() / display.height)) { previewContent() }
            }
            showFullscreen -> EngineFullscreenPreview(
                activity = activity,
                viewModel = viewModel,
                display = display,
                inputEnabled = surfaceAvailable,
                onExit = { fullscreen = false },
                content = previewContent,
            )
            else -> content(
                {
                    VirtualDisplayPreview(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                            .aspectRatio(DefaultDisplayConfig.ASPECT_RATIO)
                            .onGloballyPositioned {
                                val rect = it.boundsInWindow()
                                bounds = Rect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
                                    .takeUnless { next -> next.isEmpty }
                            },
                        isRunning = previewReady || running,
                        isSurfaceAvailable = previewReady && surfaceAvailable,
                        status = if (previewReady) VirtualDisplayPreviewStatus.RUNNING else VirtualDisplayPreviewStatus.IDLE,
                        onClick = { if (previewReady && !stopping && isActivePage) fullscreen = true },
                        content = previewContent,
                    )
                },
                { if (previewReady && !stopping && isActivePage) fullscreen = true },
            )
        }
    }
}

@Composable
private fun EngineFullscreenPreview(
    activity: Activity?,
    viewModel: EngineTaskViewModel,
    display: DisplaySpec,
    inputEnabled: Boolean,
    onExit: () -> Unit,
    content: @Composable () -> Unit,
) {
    DisposableEffect(activity) {
        val orientation = activity?.requestedOrientation
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val barsBehavior = controller?.systemBarsBehavior
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            if (orientation != null) activity?.requestedOrientation = orientation
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (barsBehavior != null) controller?.systemBarsBehavior = barsBehavior
        }
    }
    LaunchedEffect(activity) {
        if (activity?.resources?.configuration?.orientation != Configuration.ORIENTATION_LANDSCAPE) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }
    BackHandler(onBack = onExit)
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Box(Modifier.aspectRatio(display.width.toFloat() / display.height)) {
            content()
            EnginePreviewTouchOverlay(viewModel, display, inputEnabled)
        }
        // A sibling of the touch overlay: tapping Close is never forwarded to the game.
        IconButton(onClick = onExit, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            Icon(Icons.Default.Close, stringResource(R.string.task_close_preview_cd), tint = Color.White)
        }
    }
}

@Composable
private fun EnginePreviewTouchOverlay(
    viewModel: EngineTaskViewModel,
    display: DisplaySpec,
    enabled: Boolean,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var input by remember { mutableStateOf<EngineTaskViewModel.PreviewInput?>(null) }
    DisposableEffect(viewModel, lifecycle, enabled, viewportSize) {
        fun update() {
            if (enabled && viewportSize.width > 0 && viewportSize.height > 0 &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                if (input == null) input = viewModel.openPreviewInput()
            } else {
                input?.close()
                input = null
            }
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        onDispose {
            lifecycle.removeObserver(observer)
            input?.close()
            input = null
        }
    }
    val currentInput = input
    Box(Modifier.fillMaxSize().onSizeChanged { viewportSize = it }.pointerInput(currentInput, display) {
        val target = currentInput ?: return@pointerInput
        val slots = EnginePreviewPointers()
        try {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    for (change in event.changes) {
                        val id = change.id.value
                        val point = enginePreviewPoint(
                            change.position.x, change.position.y, size.width, size.height,
                            display.width, display.height,
                        ) ?: continue
                        when {
                            change.changedToDown() && point.inside -> {
                                val slot = slots.acquire(id)
                                if (slot < 0) continue
                                target.touchDown(point.x, point.y, slot)
                            }
                            change.changedToUpIgnoreConsumed() -> {
                                val slot = slots.release(id)
                                if (slot < 0) continue
                                target.touchUp(point.x, point.y, slot)
                            }
                            change.positionChangedIgnoreConsumed() -> {
                                val slot = slots.find(id)
                                if (slot < 0) continue
                                target.touchMove(point.x, point.y, slot)
                            }
                            else -> continue
                        }
                        change.consume()
                    }
                }
            }
        } finally {
            target.close()
        }
    })
}
