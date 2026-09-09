package com.aliothmoon.maadroid.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aliothmoon.maadroid.R
import com.aliothmoon.maadroid.data.datasource.ByteProgress

/** Both resource pages use the same byte-based display; unknown totals never render a percent. */
@Composable
internal fun DownloadProgressContent(
    title: String,
    bytes: ByteProgress,
    speed: String,
    actions: @Composable () -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text(speed, style = MaterialTheme.typography.labelSmall)
            actions()
        }
        val percent = bytes.percent
        Text(
            text = if (percent == null) {
                stringResource(R.string.resource_progress_unknown_size, bytes.downloadedText)
            } else {
                stringResource(R.string.resource_progress_known_size, bytes.downloadedText, bytes.totalText.orEmpty(), percent)
            },
            style = MaterialTheme.typography.bodySmall,
        )
        val fraction = bytes.fraction
        if (fraction == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
    }
}
