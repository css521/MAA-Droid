package com.aliothmoon.maadroid.presentation.view.engine

import android.graphics.PixelFormat
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aliothmoon.maadroid.R
import com.aliothmoon.maadroid.presentation.view.background.VirtualDisplayPreview
import com.aliothmoon.maadroid.presentation.view.background.VirtualDisplayPreviewStatus
import timber.log.Timber

/** The VM retains the borrowed Surface before device preparation and owns native binding. */
@Composable
internal fun EngineDisplayPreview(
    previewReady: Boolean,
    isRunning: Boolean,
    displayWidth: Int,
    displayHeight: Int,
    onSurfaceAvailable: (Surface) -> Unit,
    onSurfaceDestroyed: (Surface) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentAvailable by rememberUpdatedState(onSurfaceAvailable)
    val currentDestroyed by rememberUpdatedState(onSurfaceDestroyed)
    var surfaceAvailable by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val surfaces = remember {
        PreviewSurfaceLifecycle<Surface>(
            isValid = { it.isValid },
            onAttach = {
                failed = false
                try {
                    currentAvailable(it)
                    surfaceAvailable = true
                } catch (error: Exception) {
                    failed = true
                    Timber.w(error, "Engine preview Surface attach failed")
                }
            },
            onDetach = {
                surfaceAvailable = false
                runCatching { currentDestroyed(it) }
                    .onFailure { Timber.w(it, "Engine preview Surface detach failed") }
            },
        )
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val description = stringResource(R.string.engine_preview_description)

    DisposableEffect(surfaces, lifecycle) {
        fun updateVisibility() {
            surfaces.setVisible(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        }
        val observer = LifecycleEventObserver { _, _ -> updateVisibility() }
        lifecycle.addObserver(observer)
        updateVisibility()
        onDispose {
            lifecycle.removeObserver(observer)
            surfaces.setVisible(false)
        }
    }
    DisposableEffect(surfaces) {
        onDispose { surfaces.dispose() }
    }

    VirtualDisplayPreview(
        modifier = modifier.semantics { contentDescription = description },
        isRunning = isRunning,
        isSurfaceAvailable = previewReady && surfaceAvailable && !failed,
        status = if (isRunning) VirtualDisplayPreviewStatus.RUNNING else VirtualDisplayPreviewStatus.IDLE,
        unavailableMessage = if (failed) stringResource(R.string.engine_preview_failed) else null,
    ) {
        // Create even while idle: the VM can bind this same Surface as soon as the device is ready.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                SurfaceView(context).apply {
                    holder.setFormat(PixelFormat.RGBA_8888)
                    holder.setFixedSize(displayWidth, displayHeight)
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = Unit

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            if (width == displayWidth && height == displayHeight) {
                                surfaces.surfaceAvailable(holder.surface)
                            }
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            surfaces.surfaceDestroyed(holder.surface)
                        }
                    })
                }
            },
            onRelease = { surfaces.surfaceDestroyed(it.holder.surface) },
        )
    }
}
