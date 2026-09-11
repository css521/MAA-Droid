package com.maadroid.app.schedule.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.maadroid.app.schedule.data.ScheduleStrategyRepository
import com.maadroid.app.schedule.service.ScheduleAlarmManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import timber.log.Timber

/**
 * 设备重启或应用更新后恢复所有定时任务闹钟
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED
            && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        Timber.i("Schedule restore triggered by: %s", intent.action)
        val pendingResult = goAsync()

        val repository: ScheduleStrategyRepository = GlobalContext.get().get()
        val alarmManager: ScheduleAlarmManager = GlobalContext.get().get()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val loaded = withTimeoutOrNull(5_000L) {
                    repository.isLoaded.filter { it }.first()
                }
                if (loaded == null) {
                    Timber.w("Schedule restore: DataStore load timeout, skipping")
                    return@launch
                }
                val strategies = repository.strategies.value
                alarmManager.rescheduleAll(strategies)
                Timber.i("Schedule restore complete: %d strategies", strategies.size)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
