package com.aliothmoon.maadroid.koin

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import com.aliothmoon.maadroid.announcement.AnnouncementManager
import com.aliothmoon.maadroid.data.achievement.AchievementRepository
import com.aliothmoon.maadroid.data.api.CopilotApiService
import com.aliothmoon.maadroid.data.api.ETagCacheManager
import com.aliothmoon.maadroid.data.api.GameDataReportService
import com.aliothmoon.maadroid.data.api.HttpClientHelper
import com.aliothmoon.maadroid.data.api.MaaApiService
import com.aliothmoon.maadroid.data.api.MirrorChyanApiClient
import com.aliothmoon.maadroid.data.config.MaaPathConfig
import com.aliothmoon.maadroid.data.datasource.AppDownloader
import com.aliothmoon.maadroid.data.datasource.AssetExtractor
import com.aliothmoon.maadroid.data.datasource.ResourceDownloader
import com.aliothmoon.maadroid.data.datasource.ZipExtractor
import com.aliothmoon.maadroid.data.datasource.update.MirrorChyanAppVersionChecker
import com.aliothmoon.maadroid.data.datasource.update.MirrorChyanResourceVersionChecker
import com.aliothmoon.maadroid.data.log.ApplicationLogWriter
import com.aliothmoon.maadroid.data.notification.NotificationSettingsManager
import com.aliothmoon.maadroid.data.notification.live.AospPromotedDetector
import com.aliothmoon.maadroid.data.notification.live.FocusSequenceStore
import com.aliothmoon.maadroid.data.notification.live.HyperOsFocusDetector
import com.aliothmoon.maadroid.data.notification.live.LiveNotificationFactory
import com.aliothmoon.maadroid.data.notification.live.LivePublisherRouter
import com.aliothmoon.maadroid.data.notification.live.XmsfNetworkGate
import com.aliothmoon.maadroid.data.notification.provider.BarkProvider
import com.aliothmoon.maadroid.data.notification.provider.CustomWebhookProvider
import com.aliothmoon.maadroid.data.notification.provider.DingTalkProvider
import com.aliothmoon.maadroid.data.notification.provider.DiscordProvider
import com.aliothmoon.maadroid.data.notification.provider.DiscordWebhookProvider
import com.aliothmoon.maadroid.data.notification.provider.GotifyProvider
import com.aliothmoon.maadroid.data.notification.provider.KookProvider
import com.aliothmoon.maadroid.data.notification.provider.NotificationProvider
import com.aliothmoon.maadroid.data.notification.provider.QmsgProvider
import com.aliothmoon.maadroid.data.notification.provider.ServerChanProvider
import com.aliothmoon.maadroid.data.notification.provider.SmtpProvider
import com.aliothmoon.maadroid.data.notification.provider.TelegramProvider
import com.aliothmoon.maadroid.data.preferences.AppSettingsManager
import com.aliothmoon.maadroid.data.preferences.ConfigBackupManager
import com.aliothmoon.maadroid.data.preferences.TaskChainState
import com.aliothmoon.maadroid.data.preferences.UnlockGestureStore
import com.aliothmoon.maadroid.data.repository.CopilotRepository
import com.aliothmoon.maadroid.data.repository.DepotRepository
import com.aliothmoon.maadroid.data.repository.OperBoxRepository
import com.aliothmoon.maadroid.data.resource.ActivityManager
import com.aliothmoon.maadroid.data.background.BackgroundImageStore
import com.aliothmoon.maadroid.data.resource.CopilotResourceProvider
import com.aliothmoon.maadroid.data.resource.ItemHelper
import com.aliothmoon.maadroid.data.resource.ItemIconLoader
import com.aliothmoon.maadroid.data.resource.ResourceDataManager
import com.aliothmoon.maadroid.data.resource.StageApCostHelper
import com.aliothmoon.maadroid.domain.launch.CountdownUI
import com.aliothmoon.maadroid.domain.launch.LaunchMutex
import com.aliothmoon.maadroid.domain.launch.LaunchPipeline
import com.aliothmoon.maadroid.domain.launch.LaunchRequest
import com.aliothmoon.maadroid.domain.launch.StartTaskChainUseCase
import com.aliothmoon.maadroid.domain.notification.LiveSessionCoordinator
import com.aliothmoon.maadroid.domain.notification.LiveUpdatePublisher
import com.aliothmoon.maadroid.domain.service.AchievementReporter
import com.aliothmoon.maadroid.domain.service.AppAliveChecker
import com.aliothmoon.maadroid.domain.service.AppWatchdog
import com.aliothmoon.maadroid.domain.service.CopilotManager
import com.aliothmoon.maadroid.domain.service.CoreDataPusher
import com.aliothmoon.maadroid.domain.service.ExternalNotificationService
import com.aliothmoon.maadroid.domain.service.FightDropsRefresher
import com.aliothmoon.maadroid.domain.service.GameDataReporter
import com.aliothmoon.maadroid.domain.service.GameFpsReader
import com.aliothmoon.maadroid.domain.service.GameFpsWatcher
import com.aliothmoon.maadroid.domain.service.GameMuteCoordinator
import com.aliothmoon.maadroid.domain.service.LogExportService
import com.aliothmoon.maadroid.domain.service.MaaCompositionService
import com.aliothmoon.maadroid.domain.service.MaaEventNotifier
import com.aliothmoon.maadroid.domain.service.MaaNotificationCenter
import com.aliothmoon.maadroid.domain.service.MaaResourceLoader
import com.aliothmoon.maadroid.domain.service.MaaSessionLogger
import com.aliothmoon.maadroid.domain.service.RemoteAppAliveChecker
import com.aliothmoon.maadroid.domain.service.RemoteGameFpsReader
import com.aliothmoon.maadroid.domain.service.ResourceInitService
import com.aliothmoon.maadroid.domain.service.ScreenSaverController
import com.aliothmoon.maadroid.domain.service.TaskEndRegistry
import com.aliothmoon.maadroid.domain.service.ToolboxExportService
import com.aliothmoon.maadroid.domain.service.UnifiedStateDispatcher
import com.aliothmoon.maadroid.domain.service.UnlockGestureReader
import com.aliothmoon.maadroid.domain.service.WakeUnlockEngine
import com.aliothmoon.maadroid.domain.service.update.UpdateService
import com.aliothmoon.maadroid.domain.service.update.checker.AppVersionChecker
import com.aliothmoon.maadroid.domain.service.update.checker.ResourceVersionChecker
import com.aliothmoon.maadroid.engine.arknights.ArknightsResourcePreparation
import com.aliothmoon.maadroid.engine.arknights.MaaResourcePreparation
import com.aliothmoon.maadroid.engine.EngineTaskStore
import com.aliothmoon.maadroid.maa.callback.ConnectionInfoHandler
import com.aliothmoon.maadroid.maa.callback.CopilotRuntimeStateStore
import com.aliothmoon.maadroid.maa.callback.MaaCallbackDispatcher
import com.aliothmoon.maadroid.maa.callback.MaaExecutionStateHolder
import com.aliothmoon.maadroid.maa.callback.SubTaskHandler
import com.aliothmoon.maadroid.maa.callback.TaskChainHandler
import com.aliothmoon.maadroid.maa.callback.TaskChainStatusTracker
import com.aliothmoon.maadroid.maa.callback.ToolboxResultCollector
import com.aliothmoon.maadroid.manager.PermissionManager
import com.aliothmoon.maadroid.manager.RemoteGameAudioAdapter
import com.aliothmoon.maadroid.manager.RemoteServiceManager
import com.aliothmoon.maadroid.manager.ShizukuReadinessProvider
import com.aliothmoon.maadroid.overlay.OverlayController
import com.aliothmoon.maadroid.overlay.OverlayViewModelOwner
import com.aliothmoon.maadroid.overlay.border.BorderOverlayManager
import com.aliothmoon.maadroid.overlay.screensaver.ScreenSaverOverlayManager
import com.aliothmoon.maadroid.presentation.navigation.MainTabNavigator
import com.aliothmoon.maadroid.schedule.LaunchIntentMapper
import com.aliothmoon.maadroid.schedule.data.ScheduleStrategyRepository
import com.aliothmoon.maadroid.schedule.service.CountdownUIImpl
import com.aliothmoon.maadroid.schedule.service.ScheduleAlarmManager
import com.aliothmoon.maadroid.schedule.service.ScheduleTriggerLogger
import com.aliothmoon.maadroid.utils.CrashHandler
import com.aliothmoon.maadroid.utils.log.LogTreeHolder
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
import com.aliothmoon.maadroid.domain.models.CoreDataLocation

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
    single { com.aliothmoon.maadroid.engine.resource.EngineResourceService(androidContext(), get(), get()) }
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
