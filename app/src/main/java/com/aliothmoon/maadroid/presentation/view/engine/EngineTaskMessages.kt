package com.aliothmoon.maadroid.presentation.view.engine

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.aliothmoon.maadroid.R
import com.aliothmoon.maadroid.common.i18n.UiText
import com.aliothmoon.maadroid.common.i18n.resolve
import com.aliothmoon.maadroid.ui.asString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import java.io.File

/** No idle footer: ordinary status is transient; only explicit failures offer diagnostics. */
@Composable
internal fun EngineTaskMessages(
    status: UiText?,
    diagnosticFailure: UiText?,
    running: Boolean,
    isActivePage: Boolean,
    onExportLogs: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val snackbar = remember { SnackbarHostState() }
    var recentCrash by rememberSaveable { mutableStateOf<Long?>(null) }

    LaunchedEffect(context, lifecycle, isActivePage, running) {
        if (running) recentCrash = null
        if (!isActivePage) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val found = withContext(Dispatchers.IO) { consumeNewCrash(context) }
            if (!running && found != null) recentCrash = found
            awaitCancellation()
        }
    }
    LaunchedEffect(status, diagnosticFailure, isActivePage) {
        val message = status.resolve(context)
        if (isActivePage && diagnosticFailure == null && message.isNotBlank()) {
            snackbar.showSnackbar(message)
        }
    }
    SnackbarHost(snackbar, Modifier.fillMaxWidth().padding(horizontal = 16.dp))

    val notice = engineDiagnosticNotice(diagnosticFailure != null, recentCrash != null, running)
    if (notice != null) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (notice == EngineDiagnosticNotice.EXECUTION_FAILURE) diagnosticFailure.asString()
                    else stringResource(R.string.engine_recent_crash_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f).heightIn(max = 64.dp)
                    .verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
            )
            TextButton(onClick = onExportLogs) {
                Text(stringResource(R.string.settings_log_export_chooser_title))
            }
            if (notice == EngineDiagnosticNotice.RECENT_CRASH) {
                IconButton(onClick = { recentCrash = null }) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close))
                }
            }
        }
    }
}

/** Called on IO at resume, never per frame. Offering a crash does not consume its exported logs. */
private fun consumeNewCrash(context: Context): Long? = try {
    val timestamps = File(context.filesDir, "diagnostics/java_crashes").listFiles().orEmpty()
        .mapNotNull(::uncaughtCrashTimestamp).toMutableList()
    if (Build.VERSION.SDK_INT >= 30) {
        try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            timestamps += manager.getHistoricalProcessExitReasons(context.packageName, 0, 8)
                .filter { record ->
                    (record.processName == context.packageName || record.processName.startsWith("${context.packageName}:")) &&
                        record.reason in setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE)
                }.map { it.timestamp }
        } catch (_: Exception) {
            // Some Android versions/vendors cannot supply history. Absence is not a crash signal.
        }
    }
    val preferences = context.getSharedPreferences("engine_diagnostic_notices", Context.MODE_PRIVATE)
    val timestamp = newCrashTimestamp(timestamps, preferences.getLong("offered_through", 0), System.currentTimeMillis())
    if (timestamp != null) preferences.edit().putLong("offered_through", timestamp).commit()
    timestamp
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null // Diagnostic discovery must not break the task page.
}
