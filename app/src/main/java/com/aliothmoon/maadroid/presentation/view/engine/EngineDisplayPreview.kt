package com.aliothmoon.maadroid.presentation.view.engine

import android.graphics.PixelFormat
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aliothmoon.maadroid.R
import timber.log.Timber

/** Always visible, including when the preview Surface is absent. */
@Composable
internal fun EnginePreviewControls(
    expanded: Boolean,
    isRunning: Boolean,
    onToggleExpanded: () -> Unit,
    canEnterFullscreen: Boolean,
    onEnterFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(
                R.string.engine_preview_summary,
                stringResource(if (isRunning) R.string.virtual_display_game_running else R.string.virtual_display_idle),
            ),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onEnterFullscreen, enabled = canEnterFullscreen) {
            Icon(Icons.Default.Fullscreen, contentDescription = stringResource(R.string.engine_preview_fullscreen))
        }
        TextButton(onClick = onToggleExpanded) {
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(if (expanded) R.string.engine_preview_collapse else R.string.engine_preview_expand),
                maxLines = 1,
            )
        }
    }
}

/** The VM retains the borrowed Surface before device preparation and owns native binding. */
@Composable
internal fun EngineDisplayPreview(
    displayWidth: Int,
    displayHeight: Int,
    isActivePage: Boolean,
    onSurfaceAvailable: (Surface) -> Unit,
    onSurfaceDestroyed: (Surface) -> Unit,
    onSurfaceStateChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentAvailable by rememberUpdatedState(onSurfaceAvailable)
    val currentDestroyed by rememberUpdatedState(onSurfaceDestroyed)
    val currentSurfaceStateChanged by rememberUpdatedState(onSurfaceStateChanged)
    val surfaces = remember {
        PreviewSurfaceLifecycle<Surface>(
            isValid = { it.isValid },
            onAttach = {
                try {
                    currentAvailable(it)
                    currentSurfaceStateChanged(true)
                } catch (error: Exception) {
                    currentSurfaceStateChanged(false)
                    Timber.w(error, "Engine preview Surface attach failed")
                }
            },
            onDetach = {
                currentSurfaceStateChanged(false)
                runCatching { currentDestroyed(it) }
                    .onFailure { Timber.w(it, "Engine preview Surface detach failed") }
            },
        )
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val description = stringResource(R.string.engine_preview_description)

    DisposableEffect(surfaces, lifecycle, isActivePage) {
        fun updateVisibility() {
            // PiP is STARTED but not RESUMED: keep its picture, without enabling input.
            surfaces.setVisible(isActivePage && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
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

    // This exact composition moves between inline/fullscreen/PiP like BackgroundTaskView.
    AndroidView(
        modifier = modifier.semantics { contentDescription = description },
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
