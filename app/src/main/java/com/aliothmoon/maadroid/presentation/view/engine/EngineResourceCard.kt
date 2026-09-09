package com.aliothmoon.maadroid.presentation.view.engine

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliothmoon.maadroid.engine.ResourcePackSpec
import com.aliothmoon.maadroid.engine.resource.EngineResourceService
import com.aliothmoon.maadroid.engine.resource.ResourcePhase
import com.aliothmoon.maadroid.R
import com.aliothmoon.maadroid.presentation.components.DownloadProgressContent

/** Home uses the same installation/update state as engine preparation. */
@Composable
internal fun EngineResourceCard(
    pack: ResourcePackSpec,
    service: EngineResourceService,
    running: Boolean,
    onAction: () -> Unit,
) {
    val state by service.state(pack).collectAsStateWithLifecycle()
    val busy = state.busy
    LaunchedEffect(pack.packId, running) {
        if (!running && !service.state(pack).value.busy) service.refreshInstalled(pack)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                if (state.phase != ResourcePhase.DOWNLOADING || state.download == null) Text(when (state.phase) {
                    ResourcePhase.CHECKING -> stringResource(R.string.engine_resource_checking)
                    ResourcePhase.DOWNLOADING -> stringResource(R.string.engine_resource_connecting)
                    ResourcePhase.VERIFYING -> stringResource(R.string.resource_progress_verifying)
                    ResourcePhase.EXTRACTING -> stringResource(R.string.resource_progress_extracting, state.filesInstalled, state.filesTotal)
                    ResourcePhase.INSTALLING -> stringResource(R.string.resource_progress_installing)
                    else -> state.installedRevision?.let { stringResource(R.string.engine_resource_version, it.tag) }
                        ?: stringResource(R.string.engine_resource_not_installed)
                }, style = MaterialTheme.typography.bodySmall)
                state.error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            }
            if (!busy) TextButton(enabled = !running, onClick = onAction) { Text(when {
                state.installedVersion == null -> stringResource(R.string.engine_resource_download)
                state.phase == ResourcePhase.FAILED -> stringResource(R.string.engine_resource_retry)
                state.availableRevision != null -> stringResource(R.string.engine_resource_update, state.availableRevision!!.tag)
                else -> stringResource(R.string.engine_resource_check_update)
            }) }
        }
        val download = state.download
        if (state.phase == ResourcePhase.DOWNLOADING && download != null) {
            DownloadProgressContent(stringResource(R.string.resource_progress_downloading), download.bytes, download.speed)
        } else if (state.phase == ResourcePhase.EXTRACTING && state.filesTotal > 0) {
            LinearProgressIndicator(
                progress = { (state.filesInstalled.toFloat() / state.filesTotal).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
