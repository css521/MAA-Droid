package com.maadroid.app.koin

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import com.maadroid.app.announcement.AnnouncementManager
import com.maadroid.app.data.achievement.AchievementRepository
import com.maadroid.app.data.api.CopilotApiService
import com.maadroid.app.data.api.ETagCacheManager
import com.maadroid.app.data.api.GameDataReportService
import com.maadroid.app.data.api.HttpClientHelper
import com.maadroid.app.data.api.MaaApiService
import com.maadroid.app.data.api.MirrorChyanApiClient
import com.maadroid.app.data.config.MaaPathConfig
import com.maadroid.app.data.datasource.AppDownloader
import com.maadroid.app.data.datasource.AssetExtractor
import com.maadroid.app.data.datasource.ResourceDownloader
import com.maadroid.app.data.datasource.ZipExtractor
import com.maadroid.app.data.datasource.update.MirrorChyanAppVersionChecker
import com.maadroid.app.data.datasource.update.MirrorChyanResourceVersionChecker
import com.maadroid.app.data.log.ApplicationLogWriter
import com.maadroid.app.data.notification.NotificationSettingsManager
import com.maadroid.app.data.notification.live.AospPromotedDetector
import com.maadroid.app.data.notification.live.FocusSequenceStore
import com.maadroid.app.data.notification.live.HyperOsFocusDetector
import com.maadroid.app.data.notification.live.LiveNotificationFactory
import com.maadroid.app.data.notification.live.LivePublisherRouter
import com.maadroid.app.data.notification.live.XmsfNetworkGate
import com.maadroid.app.data.notification.provider.BarkProvider
import com.maadroid.app.data.notification.provider.CustomWebhookProvider
import com.maadroid.app.data.notification.provider.DingTalkProvider
import com.maadroid.app.data.notification.provider.DiscordProvider
import com.maadroid.app.data.notification.provider.DiscordWebhookProvider
import com.maadroid.app.data.notification.provider.GotifyProvider
import com.maadroid.app.data.notification.provider.KookProvider
import com.maadroid.app.data.notification.provider.NotificationProvider
import com.maadroid.app.data.notification.provider.QmsgProvider
import com.maadroid.app.data.notification.provider.ServerChanProvider
import com.maadroid.app.data.notification.provider.SmtpProvider
import com.maadroid.app.data.notification.provider.TelegramProvider
import com.maadroid.app.data.preferences.AppSettingsManager
import com.maadroid.app.data.preferences.ConfigBackupManager
import com.maadroid.app.data.preferences.TaskChainState
import com.maadroid.app.data.preferences.UnlockGestureStore
import com.maadroid.app.data.repository.CopilotRepository
import com.maadroid.app.data.repository.DepotRepository
import com.maadroid.app.data.repository.OperBoxRepository
import com.maadroid.app.data.resource.ActivityManager
import com.maadroid.app.data.background.BackgroundImageStore
import com.maadroid.app.data.resource.CopilotResourceProvider
import com.maadroid.app.data.resource.ItemHelper
import com.maadroid.app.data.resource.ItemIconLoader
import com.maadroid.app.data.resource.ResourceDataManager
import com.maadroid.app.data.resource.StageApCostHelper
import com.maadroid.app.domain.launch.CountdownUI
import com.maadroid.app.domain.launch.LaunchMutex
import com.maadroid.app.domain.launch.LaunchPipeline
import com.maadroid.app.domain.launch.LaunchRequest
import com.maadroid.app.domain.launch.StartTaskChainUseCase
import com.maadroid.app.domain.notification.LiveSessionCoordinator
import com.maadroid.app.domain.notification.LiveUpdatePublisher
import com.maadroid.app.domain.service.AchievementReporter
import com.maadroid.app.domain.service.AppAliveChecker
import com.maadroid.app.domain.service.AppWatchdog
import com.maadroid.app.domain.service.CopilotManager
import com.maadroid.app.domain.service.CoreDataPusher
import com.maadroid.app.domain.service.ExternalNotificationService
import com.maadroid.app.domain.service.FightDropsRefresher
import com.maadroid.app.domain.service.GameDataReporter
import com.maadroid.app.domain.service.GameFpsReader
import com.maadroid.app.domain.service.GameFpsWatcher
import com.maadroid.app.domain.service.GameMuteCoordinator
import com.maadroid.app.domain.service.LogExportService
import com.maadroid.app.domain.service.MaaCompositionService
import com.maadroid.app.domain.service.MaaEventNotifier
import com.maadroid.app.domain.service.MaaNotificationCenter
import com.maadroid.app.domain.service.MaaResourceLoader
import com.maadroid.app.domain.service.MaaSessionLogger
import com.maadroid.app.domain.service.RemoteAppAliveChecker
import com.maadroid.app.domain.service.RemoteGameFpsReader
import com.maadroid.app.domain.service.ResourceInitService
import com.maadroid.app.domain.service.ScreenSaverController
import com.maadroid.app.domain.service.TaskEndRegistry
import com.maadroid.app.domain.service.ToolboxExportService
import com.maadroid.app.domain.service.UnifiedStateDispatcher
import com.maadroid.app.domain.service.UnlockGestureReader
import com.maadroid.app.domain.service.WakeUnlockEngine
import com.maadroid.app.domain.service.update.UpdateService
import com.maadroid.app.domain.service.update.checker.AppVersionChecker
import com.maadroid.app.domain.service.update.checker.ResourceVersionChecker
import com.maadroid.app.engine.arknights.ArknightsResourcePreparation
import com.maadroid.app.engine.arknights.MaaResourcePreparation
import com.maadroid.app.engine.EngineTaskStore
import com.maadroid.app.maa.callback.ConnectionInfoHandler
import com.maadroid.app.maa.callback.CopilotRuntimeStateStore
import com.maadroid.app.maa.callback.MaaCallbackDispatcher
import com.maadroid.app.maa.callback.MaaExecutionStateHolder
import com.maadroid.app.maa.callback.SubTaskHandler
import com.maadroid.app.maa.callback.TaskChainHandler
import com.maadroid.app.maa.callback.TaskChainStatusTracker
import com.maadroid.app.maa.callback.ToolboxResultCollector
import com.maadroid.app.manager.PermissionManager
import com.maadroid.app.manager.RemoteGameAudioAdapter
import com.maadroid.app.manager.RemoteServiceManager
import com.maadroid.app.manager.ShizukuReadinessProvider
import com.maadroid.app.overlay.OverlayController
import com.maadroid.app.overlay.OverlayViewModelOwner
import com.maadroid.app.overlay.border.BorderOverlayManager
import com.maadroid.app.overlay.screensaver.ScreenSaverOverlayManager
import com.maadroid.app.presentation.navigation.MainTabNavigator
import com.maadroid.app.schedule.LaunchIntentMapper
import com.maadroid.app.schedule.data.ScheduleStrategyRepository
import com.maadroid.app.schedule.service.CountdownUIImpl
import com.maadroid.app.schedule.service.ScheduleAlarmManager
import com.maadroid.app.schedule.service.ScheduleTriggerLogger
import com.maadroid.app.utils.CrashHandler
import com.maadroid.app.utils.log.LogTreeHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import com.maadroid.app.domain.models.CoreDataLocation

val appModule = module {


    singleOf(::CrashHandler)
    single {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    singleOf(::HttpClientHelper)
    single<GameDataReporter> {
        GameDataReportService(
            appContext = androidContext(),
            httpClient = get(),
            appSettings = get(),
            taskChainState = get(),
            sessionLogger = get(),
        )
    }
    singleOf(::ETagCacheManager)
    singleOf(::MaaApiService)
    singleOf(::AnnouncementManager)
    singleOf(::PermissionManager)
    singleOf(::ShizukuReadinessProvider)
    singleOf(::MainTabNavigator)


    single {
        AppSettingsManager(
            context = get(),
            // Reporter depends on settings: resolve it after construction, on a saved change.
            onLanguageChanged = { get<AchievementReporter>().reportLanguageChanged() },
        )
    }
    single { UnlockGestureStore(get()) } bind UnlockGestureReader::class
    singleOf(::BackgroundImageStore)
    singleOf(::AchievementRepository)
    singleOf(::AchievementReporter)
    single { ScheduleStrategyRepository(androidContext()) }
    singleOf(::ScheduleTriggerLogger)
    singleOf(::ScheduleAlarmManager)
    singleOf(::LaunchMutex)
    singleOf(::StartTaskChainUseCase)
    single(named("launchPipeline")) {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
    single<CountdownUI> {
        CountdownUIImpl(
            overlayController = get(),
            onUserEvent = { event -> get<LaunchPipeline>().submit(event) },
        )
    }
    single {
        val appContext = get<Context>()
        LaunchPipeline(
            scope = get(named("launchPipeline")),
            mutex = get(),
            appSettingsManager = get(),
            wakeUnlockEngine = get(),
            unlockGestures = get(),
            chainState = get(),
            compositionService = get(),
            triggerLogger = get(),
            scheduleRepository = get(),
            startTaskChain = get(),
            countdownUI = get(),
            screenSaver = get(),
            taskEndRegistry = get(),
            keyguardLocked = {
                val km = appContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                km.isKeyguardLocked
            },
            deviceLocked = {
                val km = appContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                km.isDeviceLocked
            },
            screenInteractive = {
                val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.isInteractive
            },
            activityLauncher = { request: LaunchRequest ->
                withTimeoutOrNull(10.seconds) {
                    runCatching {
                        RemoteServiceManager.useRemoteService(timeoutMs = 8_000L) {
                            it.startActivity(LaunchIntentMapper.toShowIntent(appContext, request))
                        }
                        true
                    }.getOrDefault(false)
                } ?: false
            },
        )
    }
    singleOf(::TaskChainState)
    single { EngineTaskStore(androidContext()) }
    singleOf(::ConfigBackupManager)
    // 把「数据放哪」的设置在装配点读成布尔值再传给 MaaPathConfig ——
    // 让方舟侧不必认识 AppSettingsManager（见该类构造参数的注释）
    single {
        MaaPathConfig(
            context = get(),
            isCoreSeparated =
                get<AppSettingsManager>().coreDataLocation.value == CoreDataLocation.LOCAL_TMP,
        )
    }
    singleOf(::CoreDataPusher)
    singleOf(::ResourceDownloader)
    single { com.maadroid.app.engine.resource.EngineResourceService(androidContext(), get(), get()) }
    singleOf(::AppDownloader)
    singleOf(::ZipExtractor)
    singleOf(::AssetExtractor)

    // MirrorChyan API Client
    singleOf(::MirrorChyanApiClient)

    // Version Checkers
    single<AppVersionChecker> { MirrorChyanAppVersionChecker(get(), get()) }
    single<ResourceVersionChecker> { MirrorChyanResourceVersionChecker(get()) }

    singleOf(::UpdateService)

    singleOf(::ResourceInitService)
    singleOf(::MaaResourceLoader)
    singleOf(::ArknightsResourcePreparation) { bind<MaaResourcePreparation>() }
    singleOf(::MaaSessionLogger)

    // 外部通知
    singleOf(::NotificationSettingsManager)
    single { ServerChanProvider(get(), get()) } bind NotificationProvider::class
    single { TelegramProvider(get(), get()) } bind NotificationProvider::class
    single { DiscordProvider(get(), get()) } bind NotificationProvider::class
    single { DingTalkProvider(get(), get()) } bind NotificationProvider::class
    single { KookProvider(get(), get()) } bind NotificationProvider::class
    single { DiscordWebhookProvider(get(), get()) } bind NotificationProvider::class
    single { SmtpProvider(get()) } bind NotificationProvider::class
    single { BarkProvider(get(), get()) } bind NotificationProvider::class
    single { QmsgProvider(get(), get()) } bind NotificationProvider::class
    single { GotifyProvider(get(), get()) } bind NotificationProvider::class
    single { CustomWebhookProvider(get(), get()) } bind NotificationProvider::class
    single { ExternalNotificationService(get(), get(), getAll()) }

    // 通知 / 实况
    singleOf(::LiveNotificationFactory)
    singleOf(::AospPromotedDetector)
    singleOf(::HyperOsFocusDetector)
    singleOf(::FocusSequenceStore)
    singleOf(::XmsfNetworkGate)
    singleOf(::LivePublisherRouter)
    single<LiveUpdatePublisher> { get<LivePublisherRouter>() }
    singleOf(::LiveSessionCoordinator)
    singleOf(::MaaEventNotifier)
    singleOf(::MaaNotificationCenter)

    // 仓库 / 干员箱持久化（按配置档分片）
    single { DepotRepository.create(get(), get()) }
    single { OperBoxRepository.create(get(), get()) }

    // 回调处理链
    singleOf(::ConnectionInfoHandler)
    singleOf(::CopilotRuntimeStateStore)
    singleOf(::ToolboxResultCollector)
    singleOf(::TaskChainStatusTracker)
    singleOf(::FightDropsRefresher)
    singleOf(::TaskChainHandler)
    singleOf(::SubTaskHandler)
    single<AppAliveChecker> { RemoteAppAliveChecker() }
    singleOf(::AppWatchdog)
    single<GameFpsReader> { RemoteGameFpsReader() }
    single { GameFpsWatcher(reader = get(), sessionLogger = get(), context = androidApplication()) }
    singleOf(::MaaCompositionService)
    single<MaaExecutionStateHolder> { get<MaaCompositionService>() }
    single { GameMuteCoordinator(get(), RemoteGameAudioAdapter) }
    singleOf(::MaaCallbackDispatcher)

    // 定时唤醒 + 解锁
    singleOf(::WakeUnlockEngine)

    singleOf(::UnifiedStateDispatcher)
    // scope 走构造默认值，singleOf 会试图解析它
    single { TaskEndRegistry(compositionService = get()) }
    singleOf(::LogExportService)
    singleOf(::ToolboxExportService)


    singleOf(::BorderOverlayManager)
    singleOf(::ScreenSaverOverlayManager) { bind<ScreenSaverController>() }
    singleOf(::OverlayViewModelOwner)
    singleOf(::OverlayController)


    singleOf(::ItemHelper)
    singleOf(::StageApCostHelper)
    singleOf(::ItemIconLoader)
    // clientType 以 provider 注入，避免 data/resource 依赖方舟任务链状态机（见 ActivityManager）
    single {
        ActivityManager(
            context = get(),
            clientTypeProvider = { get<TaskChainState>().clientType },
            maaApiService = get(),
            itemHelper = get(),
        )
    }
    singleOf(::ResourceDataManager)
    // Copilot (自动战斗)
    singleOf(::CopilotApiService)
    singleOf(::CopilotRepository)
    singleOf(::CopilotManager)
    singleOf(::CopilotResourceProvider)
    singleOf(::ApplicationLogWriter)
    singleOf(::LogTreeHolder)

    // 前台模式自动任务
}
