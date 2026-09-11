package com.maadroid.app

import android.app.Application
import com.maadroid.app.diagnostics.AppDiagnostics
import com.maadroid.app.data.config.MaaPathConfig
import com.maadroid.app.data.datasource.AppDownloader
import com.maadroid.app.data.preferences.AppSettingsManager
import com.maadroid.app.data.repository.DepotRepository
import com.maadroid.app.data.repository.OperBoxRepository
import com.maadroid.app.domain.service.GameMuteCoordinator
import com.maadroid.app.domain.service.TaskEndRegistry
import com.maadroid.app.domain.service.UnifiedStateDispatcher
import com.maadroid.app.koin.appModule
import com.maadroid.app.koin.floatingWindowModule
import com.maadroid.app.koin.useCaseModule
import com.maadroid.app.koin.viewModelModule
import com.maadroid.app.manager.RemoteServiceManager
import com.maadroid.app.overlay.OverlayController
import com.maadroid.app.schedule.data.ScheduleStrategyRepository
import com.maadroid.app.schedule.service.ScheduleAlarmManager
import com.maadroid.app.utils.CrashHandler
import com.maadroid.app.utils.i18n.LocaleBootstrap
import com.maadroid.app.utils.log.LogTreeHolder
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
import com.maadroid.app.engine.EngineSetup

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
        AppDiagnostics.initialize(this)
        CrashHandler.installEarly(this)
        // 引擎装配先于 Koin：宿主的 ViewModel 会读 EngineRegistry 决定显示哪些游戏
        EngineSetup.install()
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
