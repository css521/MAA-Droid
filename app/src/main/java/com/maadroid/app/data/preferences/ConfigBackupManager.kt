package com.maadroid.app.data.preferences

import com.maadroid.app.constant.OFFICIAL_SHIZUKU_PACKAGE
import com.maadroid.app.data.model.InfrastConfig
import com.maadroid.app.data.model.TaskProfile
import com.maadroid.app.data.notification.NotificationSettings
import com.maadroid.app.data.notification.NotificationSettingsManager
import com.maadroid.app.data.notification.reapplyWebhookPresetIfBlank
import com.maadroid.app.domain.models.AppSettings
import com.maadroid.app.engine.EngineTaskStore
import com.maadroid.app.engine.arknights.enums.InfrastMode
import com.maadroid.app.engine.arknights.enums.UiUsageConstants
import com.maadroid.app.schedule.data.ScheduleStrategyRepository
import com.maadroid.app.schedule.service.ScheduleAlarmManager
import com.maadroid.app.utils.JsonUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ConfigBackupManager(
    private val appSettingsManager: AppSettingsManager,
    private val notificationSettingsManager: NotificationSettingsManager,
    private val taskChainState: TaskChainState,
    private val scheduleStrategyRepository: ScheduleStrategyRepository,
    private val scheduleAlarmManager: ScheduleAlarmManager,
    private val engineTaskStore: EngineTaskStore,
) {
    private val operation = Mutex()
    private val json = Json(JsonUtils.common) {
        prettyPrint = true
    }

    private suspend fun storageOperation(block: suspend () -> Unit) =
        withContext(Dispatchers.IO) { operation.withLock { block() } }

    suspend fun exportTo(outputStream: OutputStream) = outputStream.bufferedWriter().use { writer ->
        storageOperation {
            // 等待异步数据加载完成，避免导出空数据
            taskChainState.isLoaded.first { it }

            val backup = ConfigBackup(
                version = CURRENT_VERSION,
                exportedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                appSettings = appSettingsManager.settings.first().sanitized(),
                notificationSettings = notificationSettingsManager.settings.first()
                    .sanitizedForExport(),
                taskProfiles = taskChainState.profiles.value.map { it.sanitized() },
                activeProfileId = taskChainState.profileId.value,
                scheduleStrategies = scheduleStrategyRepository.snapshot(),
                engineTasks = engineTaskStore.exportSnapshot(),
            )
            writer.write(json.encodeToString(ConfigBackup.serializer(), backup))
            writer.flush()
        }
    }

    /**
     * 导入配置。
     * 注意：AppSettings 采用整包写入，部分设置（如 startupBackend、debugMode）的运行态副作用
     * 不会立刻触发，建议导入后重启应用以确保所有设置完全生效。
     */
    suspend fun importFrom(inputStream: InputStream) {
        // Finish and close the supplied document before any mutation. Even cancellation
        // before the IO dispatcher starts, or an input close failure, leaves no open stream.
        val content = inputStream.bufferedReader().use { reader ->
            withContext(Dispatchers.IO) { reader.readText() }
        }
        importContent(content)
    }

    private suspend fun importContent(content: String) = operation.withLock {
        // Keep the catch outside the dispatcher switch: cancellation can arrive while
        // synchronous alarm calls finish and only be thrown by withContext on its return.
        var rollback: suspend (Exception) -> Boolean = { false }
        try {
            withContext(Dispatchers.IO) {
                val backup = json.decodeFromString(ConfigBackup.serializer(), content)
                require(backup.version in 1..CURRENT_VERSION) {
                    "不支持的备份版本: ${backup.version}，当前最高支持: $CURRENT_VERSION"
                }
                // Syntax only; an incomplete team is still a valid engine-owned draft.
                val engineTasks = EngineTaskStore.validateSnapshot(backup.engineTasks)
                taskChainState.isLoaded.first { it }
                val localSettings = appSettingsManager.settings.first()
                val localNotifications = notificationSettingsManager.settings.first()
                val localProfiles = taskChainState.profiles.value
                val localActiveProfile = taskChainState.profileId.value
                require(localProfiles.isNotEmpty()) { "当前任务配置尚未加载，无法导入" }
                val engineCheckpoint = engineTaskStore.checkpoint(engineTasks.keys)
                val oldStrategies = scheduleStrategyRepository.snapshot()
                val undo = mutableListOf<suspend () -> Unit>()
                var alarmsTouched = false
                rollback = { failure ->
                    var failed = false
                    suspend fun recover(action: suspend () -> Unit) {
                        try { action() } catch (restoreFailure: Exception) {
                            failed = true
                            if (restoreFailure !== failure) failure.addSuppressed(restoreFailure)
                        }
                    }
                    if (alarmsTouched) {
                        (oldStrategies + backup.scheduleStrategies).map { it.id }.distinct().forEach { id ->
                            recover { scheduleAlarmManager.cancel(id) }
                        }
                    }
                    undo.asReversed().forEach { recover(it) }
                    if (alarmsTouched) oldStrategies.forEach { strategy ->
                        recover { scheduleAlarmManager.rescheduleAll(listOf(strategy)) }
                    }
                    failed
                }
                // Undo is registered before each attempt, including writes that change
                // memory before their disk flush, or commit just before cancellation.
                suspend fun change(restore: suspend () -> Unit, apply: suspend () -> Unit) {
                    undo += restore
                    apply()
                }
                change({ appSettingsManager.setSettings(localSettings) }) {
                    appSettingsManager.setSettings(backup.appSettings.normalizedForImport().copy(
                        customBackgroundEnabled = localSettings.customBackgroundEnabled,
                        customBackgroundToken = localSettings.customBackgroundToken,
                    ))
                }
                change({ notificationSettingsManager.updateSettings(localNotifications) }) {
                    notificationSettingsManager.updateSettings(backup.notificationSettings.reapplyWebhookPresetIfBlank())
                }
                change({ taskChainState.importProfiles(localProfiles, localActiveProfile) }) {
                    taskChainState.importProfiles(backup.taskProfiles, backup.activeProfileId)
                }
                change({ engineTaskStore.restore(engineCheckpoint) }) { engineTaskStore.importSnapshot(engineTasks) }
                change({ scheduleStrategyRepository.importStrategies(oldStrategies) }) {
                    scheduleStrategyRepository.importStrategies(backup.scheduleStrategies)
                }
                // Only touch OS alarms after all persistent writes have succeeded.
                alarmsTouched = true
                oldStrategies.forEach { scheduleAlarmManager.cancel(it.id) }
                scheduleAlarmManager.rescheduleAll(backup.scheduleStrategies)
            }
        } catch (failure: Exception) {
            withContext(NonCancellable + Dispatchers.IO) {
                val restoreFailed = rollback(failure)
                if (restoreFailed) {
                    if (failure is CancellationException) throw IncompleteRestoreCancellationException(failure)
                    throw IllegalStateException("配置导入失败，部分旧配置未能恢复，请检查存储后重新导入", failure)
                }
                // Throw inside this boundary too. Returning a Boolean to a cancelled
                // dispatcher would discard it before the caller can report failed undo.
                throw failure
            }
        }
    }

    companion object {
        const val CURRENT_VERSION = 2

        /**
         * 导出时剥离设备本地字段：CDK 与解锁 PIN 属敏感信息；
         * 自定义背景的开关与令牌对应本机 filesDir 下的图片文件，在其他设备上不存在。
         */
        private fun AppSettings.sanitized() = copy(
            mirrorChyanCdk = "",
            wakeCredential = "",
            customBackgroundEnabled = "false",
            customBackgroundToken = "",
        )

        /**
         * 导入时对已废弃或非法的旧值做归一化，避免后续读取时违反非空约束。
         */
        private fun AppSettings.normalizedForImport() = copy(
            shizukuLaunchPackage = shizukuLaunchPackage.ifBlank { OFFICIAL_SHIZUKU_PACKAGE }
        )

        /**
         * 导出时将使用自定义文件的基建配置回退为常规模式，
         * 因为自定义文件路径在其他设备上不存在。
         */
        private fun TaskProfile.sanitized() = copy(
            chain = chain.map { node ->
                val cfg = node.config
                if (cfg is InfrastConfig
                    && cfg.mode == InfrastMode.Custom
                    && cfg.defaultInfrast == UiUsageConstants.USER_DEFINED_INFRAST
                ) {
                    node.copy(config = InfrastConfig())
                } else {
                    node
                }
            }
        )
    }
}

// 导出脱敏：凭证清空，识别类字段（userId/chatId 等）保留
internal fun NotificationSettings.sanitizedForExport() = copy(
    serverChanSendKey = "",
    discordBotToken = "",
    discordWebhookUrl = "",
    smtpPassword = "",
    barkSendKey = "",
    telegramBotToken = "",
    dingTalkAccessToken = "",
    dingTalkSecret = "",
    kookBotToken = "",
    qmsgKey = "",
    gotifyToken = "",
    customWebhookUrl = "",
    customWebhookHeaders = "",
)

/** Cancellation still propagates, while the caller can surface incomplete recovery distinctly. */
internal class IncompleteRestoreCancellationException(cause: CancellationException) :
    CancellationException("配置导入已取消，部分旧配置未能恢复，请检查存储后重新导入") {
    init { initCause(cause) }
}
