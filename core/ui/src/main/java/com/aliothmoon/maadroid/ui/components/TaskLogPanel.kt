package com.aliothmoon.maadroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Presentation only: callers keep their original log models, details and export actions. */
@Composable
fun TaskLogPanel(
    title: String,
    count: Int,
    latestDescription: String,
    modifier: Modifier = Modifier,
    key: (Int) -> Any = { it },
    lastEntryKey: Any? = count,
    actions: @Composable RowScope.() -> Unit = {},
    emptyContent: @Composable () -> Unit = {},
    line: @Composable (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    var autoScroll by remember { mutableStateOf(true) }
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragging) { if (dragging) autoScroll = false }
    LaunchedEffect(lastEntryKey, autoScroll) {
        if (autoScroll && count > 0) listState.scrollToItem(count - 1)
    }
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Rounded.List, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Row(content = actions)
        }
        Box(Modifier.weight(1f)) {
            if (count == 0) Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                emptyContent()
            } else LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(count, key = key) { line(it) }
            }
            if (listState.canScrollForward && count > 0) {
                IconButton(
                    onClick = { autoScroll = true },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(40.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, latestDescription,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
fun TaskLogBadge(label: String, color: Color, compact: Boolean = true) {
    Surface(shape = RoundedCornerShape(if (compact) 3.dp else 4.dp), color = color.copy(alpha = 0.15f)) {
        Text(
            label,
            style = if (compact) MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Medium)
                else MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = if (compact) 4.dp else 6.dp, vertical = if (compact) 1.dp else 2.dp),
        )
    }
}

@Composable
fun TaskLogLine(
    message: String,
    color: Color,
    levelLabel: String? = null,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    leading: @Composable RowScope.() -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        leading()
        if (levelLabel != null) {
            TaskLogBadge(levelLabel, color)
            Spacer(Modifier.width(6.dp))
        }
        Text(message, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing()
    }
}
