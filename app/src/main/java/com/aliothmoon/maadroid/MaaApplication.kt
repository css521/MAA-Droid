package com.aliothmoon.maadroid

import android.app.Application
import com.aliothmoon.maadroid.data.config.MaaPathConfig
import com.aliothmoon.maadroid.data.datasource.AppDownloader
import com.aliothmoon.maadroid.data.preferences.AppSettingsManager
import com.aliothmoon.maadroid.data.repository.DepotRepository
import com.aliothmoon.maadroid.data.repository.OperBoxRepository
import com.aliothmoon.maadroid.domain.service.GameMuteCoordinator
import com.aliothmoon.maadroid.domain.service.TaskEndRegistry
import com.aliothmoon.maadroid.domain.service.UnifiedStateDispatcher
import com.aliothmoon.maadroid.koin.appModule
import com.aliothmoon.maadroid.koin.floatingWindowModule
import com.aliothmoon.maadroid.koin.useCaseModule
import com.aliothmoon.maadroid.koin.viewModelModule
import com.aliothmoon.maadroid.manager.RemoteServiceManager
import com.aliothmoon.maadroid.overlay.OverlayController
import com.aliothmoon.maadroid.schedule.data.ScheduleStrategyRepository
import com.aliothmoon.maadroid.schedule.service.ScheduleAlarmManager
import com.aliothmoon.maadroid.utils.CrashHandler
import com.aliothmoon.maadroid.utils.i18n.LocaleBootstrap
import com.aliothmoon.maadroid.utils.log.LogTreeHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class MaaApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val appSettingsManager: AppSettingsManager by inject()
    private val pathConfig: MaaPathConfig by inject()
    private val crashHandler: CrashHandler by inject()
    private val unifiedStateDispatcher: UnifiedStateDispatcher by inject()
    private val taskEndRegistry: TaskEndRegistry by inject()
    private val gameMuteCoordinator: GameMuteCoordinator by inject()
    private val overlayController: OverlayController by inject()
    private val appDownloader: AppDownloader by inject()
    private val treeHolder: LogTreeHolder by inject()
    private val scheduleRepository: ScheduleStrategyRepository by inject()
    private val scheduleAlarmManager: ScheduleAlarmManager by inject()
    private val depotRepository: DepotRepository by inject()
    private val operBoxRepository: OperBoxRepository by inject()


    override fun onCreate() {
        super.onCreate()
        val app = this
        startKoin {
            androidLogger(if (BuildConfig.DEBUG) Level.DEBUG else Level.NONE)
            androidContext(app)
            modules(appModule, useCaseModule, viewModelModule, floatingWindowModule)
        }

        LocaleBootstrap.applyPersisted(appSettingsManager)

        postCreateApplication()
    }

    private fun postCreateApplication() {
        treeHolder.setup()
        RemoteServiceManager.initialize(this, appSettingsManager, pathConfig)
        crashHandler.init(this)
        overlayController.setup()
        unifiedStateDispatcher.start()
        taskEndRegistry.start()
        gameMuteCoordinator.startAutoRestore()
        depotRepository.start()
        operBoxRepository.start()
        cleanCachedUpdateApks()
        doSyncScheduleAlarms()
    }

    private fun cleanCachedUpdateApks() {
        applicationScope.launch {
            appDownloader.cleanInstalledApks()
        }
    }

    // BootReceiver 依赖 ACTION_MY_PACKAGE_REPLACED / BOOT_COMPLETED 恢复闹钟，
    // 但国产 ROM 在自启动未开启时会拦截该广播，导致闹钟丢失后无法恢复。
    // 每次应用启动时执行一次幂等同步，作为兜底保障。
    private fun doSyncScheduleAlarms() {
        applicationScope.launch {
            scheduleRepository.isLoaded.filter { it }.first()
            scheduleAlarmManager.rescheduleAll(scheduleRepository.strategies.value)
        }
    }
}
