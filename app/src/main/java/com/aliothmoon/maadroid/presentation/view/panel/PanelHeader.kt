package com.aliothmoon.maadroid.presentation.view.panel

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aliothmoon.maadroid.R
import com.aliothmoon.maadroid.ui.components.TaskPanelTabs

/**
 * 面板标题栏
 */
@Composable
fun PanelHeader(
    selectedTab: PanelTab = PanelTab.TASKS,
    onTabSelected: (PanelTab) -> Unit = {},
    showActions: Boolean = true,
    isLocked: Boolean = false,
    onLockToggle: (Boolean) -> Unit = {},
    onHome: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    TaskPanelTabs(
        labels = PanelTab.entries.map { stringResource(it.labelRes) },
        selectedIndex = selectedTab.ordinal,
        onSelected = { onTabSelected(PanelTab.entries[it]) },
        modifier = modifier,
    ) {
        if (showActions) {
            IconButton(onClick = onHome, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Home, stringResource(R.string.panel_cd_go_home),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = { onLockToggle(!isLocked) }, modifier = Modifier.size(32.dp)) {
                Icon(if (isLocked) Icons.Filled.Lock else Icons.Outlined.Lock, contentDescription = null,
                    tint = if (isLocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp))
            }
        }
    }
}
