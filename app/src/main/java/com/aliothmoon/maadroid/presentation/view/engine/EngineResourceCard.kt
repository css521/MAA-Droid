package com.aliothmoon.maadroid.presentation.view.engine

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliothmoon.maadroid.engine.ResourcePackSpec
import com.aliothmoon.maadroid.engine.resource.EngineResourceService
import com.aliothmoon.maadroid.engine.resource.ResourcePhase
import kotlinx.coroutines.launch

/** 当前游戏就地安装/更新资源，避免引导用户去不存在的资源中心。 */
@Composable
internal fun EngineResourceCard(pack: ResourcePackSpec, service: EngineResourceService, running: Boolean) {
    val state by service.state(pack).collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val busy = state.phase in setOf(ResourcePhase.CHECKING, ResourcePhase.DOWNLOADING, ResourcePhase.INSTALLING)
    LaunchedEffect(pack.packId) { if (!running) service.refreshInstalled(pack) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(when (state.phase) {
                    ResourcePhase.CHECKING -> "正在检查资源…"
                    ResourcePhase.DOWNLOADING -> state.download?.let { "下载资源 ${it.progress}% · ${it.speed}" } ?: "正在连接资源服务器…"
                    ResourcePhase.INSTALLING -> "校验并安装资源 ${state.filesInstalled}/${state.filesTotal}"
                    else -> state.installedRevision?.let { "资源 ${it.tag}" } ?: "自动化资源尚未安装"
                }, style = MaterialTheme.typography.bodySmall)
                state.error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            }
            if (!busy) TextButton(enabled = !running, onClick = { scope.launch {
                when {
                    state.installedVersion == null -> service.ensureInstalled(pack)
                    state.phase == ResourcePhase.FAILED -> service.ensureInstalled(pack)
                    state.availableRevision != null -> service.update(pack)
                    else -> service.checkForUpdate(pack)
                }
            } }) { Text(when {
                state.installedVersion == null -> "下载资源"
                state.phase == ResourcePhase.FAILED -> "修复 / 重试"
                state.availableRevision != null -> "更新 ${state.availableRevision!!.tag}"
                else -> "检查更新"
            }) }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
