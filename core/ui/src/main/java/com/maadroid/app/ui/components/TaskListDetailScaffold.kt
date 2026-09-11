package com.maadroid.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared task rail/detail viewport, including the bounded floating-panel fallback. */
@Composable
fun TaskListDetailScaffold(
    modifier: Modifier = Modifier,
    wrapDetailInCard: Boolean = false,
    taskList: @Composable (Modifier) -> Unit,
    detail: @Composable () -> Unit,
) {
    val floatMaxWidth = (LocalConfiguration.current.screenWidthDp * 0.85f).dp
    BoxWithConstraints(modifier.fillMaxSize()) {
        val contentModifier = if (constraints.hasBoundedWidth) Modifier.fillMaxSize()
        else Modifier.widthIn(max = floatMaxWidth).fillMaxHeight().fillMaxWidth()
        Row(contentModifier) {
            taskList(Modifier.fillMaxHeight().width(IntrinsicSize.Max))
            Spacer(Modifier.width(8.dp))
            val detailModifier = Modifier.weight(1f).fillMaxHeight()
            if (wrapDetailInCard) MaaSurfaceCard(detailModifier) {
                Box(Modifier.fillMaxSize().padding(top = 10.dp)) { detail() }
            } else Box(detailModifier) { detail() }
        }
    }
}

/** Selecting a task opens its details; only the checkbox changes its enabled state. */
@Composable
fun TaskSelectionRow(
    label: String,
    selected: Boolean,
    onSelected: () -> Unit,
    modifier: Modifier = Modifier,
    checked: Boolean? = null,
    onCheckedChange: (Boolean) -> Unit = {},
    editable: Boolean = true,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(4.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)) else null,
    ) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onSelected).padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (checked != null) {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                    Checkbox(checked, onCheckedChange, enabled = editable, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
}
