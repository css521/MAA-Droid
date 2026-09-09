package com.aliothmoon.maadroid.domain.service.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.aliothmoon.maadroid.BuildConfig
import com.aliothmoon.maadroid.R
import com.aliothmoon.maadroid.constant.MaaFiles
import com.aliothmoon.maadroid.constant.AppApi
import com.aliothmoon.maadroid.common.i18n.UiText
import com.aliothmoon.maadroid.data.achievement.AchievementEvents
import com.aliothmoon.maadroid.data.achievement.AchievementRepository
import com.aliothmoon.maadroid.data.api.CdkRequiredException
import com.aliothmoon.maadroid.data.api.HttpClientHelper
import com.aliothmoon.maadroid.data.api.MirrorChyanApiClient
import com.aliothmoon.maadroid.data.api.MirrorChyanBizException
import com.aliothmoon.maadroid.data.datasource.AppDownloader
import com.aliothmoon.maadroid.data.datasource.ResourceDownloader
import com.aliothmoon.maadroid.data.datasource.ZipExtractor
import com.aliothmoon.maadroid.data.datasource.update.GitHubAppDownloadUrlResolver
import com.aliothmoon.maadroid.data.datasource.update.GitHubAppVersionChecker
import com.aliothmoon.maadroid.data.datasource.update.GitHubResourceDownloadUrlResolver
import com.aliothmoon.maadroid.data.datasource.update.MirrorChyanAppDownloadUrlResolver
import com.aliothmoon.maadroid.data.datasource.update.MirrorChyanResourceDownloadUrlResolver
import com.aliothmoon.maadroid.data.model.update.UpdateChannel
import com.aliothmoon.maadroid.data.model.update.AppUpdateSourceUnavailableException
import com.aliothmoon.maadroid.data.model.update.UpdateCheckResult
import com.aliothmoon.maadroid.data.model.update.UpdateError
import com.aliothmoon.maadroid.data.model.update.UpdateError.MirrorchyanBizError
import com.aliothmoon.maadroid.data.model.update.UpdateProcessState
import com.aliothmoon.maadroid.data.model.update.UpdateSource
import com.aliothmoon.maadroid.data.preferences.AppSettingsManager
import com.aliothmoon.maadroid.domain.service.CoreDataPusher
import com.aliothmoon.maadroid.domain.service.update.checker.AppVersionChecker
import com.aliothmoon.maadroid.domain.service.update.checker.ConfiguredAppVersionChecker
import com.aliothmoon.maadroid.domain.service.update.checker.ResourceVersionChecker
import com.aliothmoon.maadroid.domain.service.update.resolver.AppDownloadUrlResolver
import com.aliothmoon.maadroid.domain.service.update.resolver.ResourceDownloadUrlResolver
import com.aliothmoon.maadroid.engine.ResourcePackSpec
import com.aliothmoon.maadroid.engine.arknights.resource.MaaResourcePack
import com.aliothmoon.maadroid.common.i18n.LocalizedException
import com.aliothmoon.maadroid.common.i18n.resolve
import com.aliothmoon.maadroid.common.i18n.uiTextOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 更新服务 — 直接编排版本检查、下载链接解析、下载安装全流程
 */
class UpdateService(
    private val context: Context,
    apiClient: MirrorChyanApiClient,
    appSettingsManager: AppSettingsManager,
    httpClient: HttpClientHelper,
    appVersionChecker: AppVersionChecker,
    private val resourceVersionChecker: ResourceVersionChecker,
    private val appDownloader: AppDownloader,
    private val resourceDownloader: ResourceDownloader,
    private val extractor: ZipExtractor,
    private val achievementRepository: AchievementRepository,
    private val coreDataPusher: CoreDataPusher,
) {
    private val configuredAppVersionChecker = ConfiguredAppVersionChecker(
        github = GitHubAppVersionChecker(httpClient),
        mirrorChyan = appVersionChecker,
    )

    private val appDownloadResolvers: Map<UpdateSource, AppDownloadUrlResolver> = mapOf(
        UpdateSource.MIRROR_CHYAN to MirrorChyanAppDownloadUrlResolver(
            apiClient,
            appSettingsManager
        ),
        UpdateSource.GITHUB to GitHubAppDownloadUrlResolver(httpClient)
    )

    private val resourceDownloadResolvers: Map<UpdateSource, ResourceDownloadUrlResolver> = mapOf(
        UpdateSource.MIRROR_CHYAN to MirrorChyanResourceDownloadUrlResolver(
            apiClient,
            appSettingsManager
        ),
        UpdateSource.GITHUB to GitHubResourceDownloadUrlResolver()
    )

    // ==================== App 更新 ====================

    private val appDownloading = AtomicBoolean(false)

    @Volatile
    private var appDownloadJob: Job? = null

    private val _appProcessState = MutableStateFlow<UpdateProcessState>(UpdateProcessState.Idle)
    val appProcessState: StateFlow<UpdateProcessState> = _appProcessState.asStateFlow()

    suspend fun checkAppUpdate(channel: UpdateChannel = UpdateChannel.STABLE): UpdateCheckResult {
        return configuredAppVersionChecker.check(BuildConfig.VERSION_NAME, channel)
    }

    suspend fun downloadApp(
        source: UpdateSource,
        version: String,
        channel: UpdateChannel = UpdateChannel.STABLE
    ): Result<Unit> {
        // 在解析链接、读取缓存、启动安装器之前拒绝未配置的 APK 分发源。
        val unavailableReason = if (source == UpdateSource.MIRROR_CHYAN) {
            AppApi.APP_UPDATE_SOURCE.mirrorChyanUnavailableReason
        } else {
            AppApi.APP_UPDATE_SOURCE.disabledReason
        }
        unavailableReason?.let {
            return failApp(UpdateError.UnknownError(UiText.Dynamic(it)))
        }
        if (!appDownloading.compareAndSet(false, true)) {
            return Result.success(Unit)   // 已在进行中，幂等跳过
        }
        appDownloadJob = currentCoroutineContext().job
        try {
            Timber.i(
                "downloadApp start: source=%s, version=%s, channel=%s",
                source,
                version,
                channel
            )
            _appProcessState.value = UpdateProcessState.Downloading(
                0,
                context.getString(R.string.update_preparing_download),
                0L,
                0L
            )

            val resolver = appDownloadResolvers[source]
                ?: return failApp(
                    UpdateError.UnknownError(
                        uiTextOf(
                            R.string.update_error_unsupported_source,
                            source
                        )
                    )
                )

            val url = resolver.resolve(version, channel).getOrElse { e ->
                // 解析器内部是 runCatching，取消也会落进 failure
                currentCoroutineContext().ensureActive()
                val error = mapToUpdateError(e)
                _appProcessState.value = UpdateProcessState.Failed(error)
                achievementRepository.report {
                    event = AchievementEvents.UPDATE_FAILED
                    payload(updateAchievementPayload(kind = "app", source = source, error = error))
                    "channel" to channel.name
                }
                return Result.failure(e)
            }
            Timber.i("downloadApp resolved URL: host=%s", safeHost(url))
            val result = downloadAndInstallApp(url, version)
            achievementRepository.report {
                event =
                    if (result.isSuccess) AchievementEvents.UPDATE_COMPLETED else AchievementEvents.UPDATE_FAILED
                "kind" to "app"
                "source" to source.name
                "channel" to channel.name
                "version" to version
            }
            return result
        } catch (e: CancellationException) {
            // 取消不算失败，不弹错误弹窗也不上报成就
            Timber.i("downloadApp canceled")
            _appProcessState.value = UpdateProcessState.Idle
            throw e
        } finally {
            appDownloadJob = null
            appDownloading.set(false)
        }
    }

    /** 必须 join：不然紧接着换源重下会撞上 [appDownloading] 幂等门被静默跳过 */
    suspend fun cancelAppDownload() {
        appDownloadJob?.cancelAndJoin()
    }

    fun resetAppProcess() {
        _appProcessState.value = UpdateProcessState.Idle
    }

    private suspend fun downloadAndInstallApp(url: String, version: String): Result<Unit> {
        val cached = appDownloader.getCachedApk(version)
        if (cached != null) {
            Timber.i("APK already cached: ${cached.name}, skipping download")
            return doInstallApp(cached)
        }

        appDownloader.cleanOldApks(version)

        val downloadResult = appDownloader.downloadToTempFile(url, version) { progress ->
            _appProcessState.value = UpdateProcessState.Downloading(
                progress = progress.progress,
                speed = progress.speed,
                downloaded = progress.downloaded,
                total = progress.total
            )
        }

        val apkFile = downloadResult.getOrElse { e ->
            _appProcessState.value =
                UpdateProcessState.Failed(mapToUpdateError(e))
            return Result.failure(e)
        }

        // 卡在「下完了还没装」的缝里被取消，别再弹安装界面
        currentCoroutineContext().ensureActive()

        return doInstallApp(apkFile)
    }

    private fun doInstallApp(apkFile: File): Result<Unit> {
        _appProcessState.value = UpdateProcessState.Installing
        return try {
            installApk(apkFile)
            _appProcessState.value = UpdateProcessState.Success
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to install APK")
            _appProcessState.value =
                UpdateProcessState.Failed(
                    UpdateError.UnknownError(
                        uiTextOf(
                            R.string.update_error_install_failed,
                            e.message?.takeIf { it.isNotBlank() }
                                ?: context.getString(R.string.update_error_unknown)
                        )
                    )
                )
            Result.failure(e)
        }
    }

    private fun installApk(apkFile: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    private fun failApp(error: UpdateError): Result<Unit> {
        _appProcessState.value = UpdateProcessState.Failed(error)
        return Result.failure(Exception(error.text.resolve(context)))
    }

    // ==================== 资源更新 ====================

    private val resourceDownloading = AtomicBoolean(false)

    @Volatile
    private var resourceDownloadJob: Job? = null

    private val _resourceProcessState =
        MutableStateFlow<UpdateProcessState>(UpdateProcessState.Idle)
    val resourceProcessState: StateFlow<UpdateProcessState> = _resourceProcessState.asStateFlow()

    suspend fun checkResourceUpdate(currentVersion: String): UpdateCheckResult {
        return resourceVersionChecker.check(currentVersion)
    }

    /**
     * 下载并解包一个资源包。
     *
     * [pack] 决定 zip 条目怎么落盘、失败时抹哪个版本标记 —— 两个引擎的上游打包布局
     * 不同（方舟的 MaaResource 带顶层目录，我们给边狱重打的包是平铺的），
     * 靠 [ResourcePackSpec] 吸收差异，这里不认识任何具体游戏。
     *
     * 缺省是方舟包，保持既有调用方与行为不变。
     */
    suspend fun downloadResource(
        source: UpdateSource,
        currentVersion: String,
        target: File,
        pack: ResourcePackSpec = MaaResourcePack,
    ): Result<Unit> {
        if (pack.upstreamArchive != null) {
            return Result.failure(IllegalArgumentException("此资源包请在对应游戏任务页更新"))
        }
        if (!resourceDownloading.compareAndSet(false, true)) {
            return Result.success(Unit)   // 已在进行中，幂等跳过
        }
        resourceDownloadJob = currentCoroutineContext().job
        try {
            Timber.i("downloadResource start: source=%s", source)
            _resourceProcessState.value = UpdateProcessState.Downloading(
                0,
                context.getString(R.string.update_preparing_download),
                0L,
                0L
            )

            val resolver = resourceDownloadResolvers[source]
                ?: return failResource(
                    UpdateError.UnknownError(
                        uiTextOf(
                            R.string.update_error_unsupported_source,
                            source
                        )
                    )
                )

            val url = resolver.resolve(currentVersion).getOrElse { e ->
                currentCoroutineContext().ensureActive()
                val error = mapToUpdateError(e)
                _resourceProcessState.value = UpdateProcessState.Failed(error)
                achievementRepository.report {
                    event = AchievementEvents.UPDATE_FAILED
                    payload(
                        updateAchievementPayload(
                            kind = "resource",
                            source = source,
                            error = error,
                        ),
                    )
                }
                return Result.failure(e)
            }
            Timber.i("downloadResource resolved URL: host=%s", safeHost(url))
            val result = downloadAndExtractResource(target, url, pack)
            achievementRepository.report {
                event =
                    if (result.isSuccess) AchievementEvents.UPDATE_COMPLETED else AchievementEvents.UPDATE_FAILED
                "kind" to "resource"
                "source" to source.name
            }
            return result
        } catch (e: CancellationException) {
            Timber.i("downloadResource canceled")
            _resourceProcessState.value = UpdateProcessState.Idle
            throw e
        } finally {
            resourceDownloadJob = null
            resourceDownloading.set(false)
        }
    }

    /** 同 [cancelAppDownload] */
    suspend fun cancelResourceDownload() {
        resourceDownloadJob?.cancelAndJoin()
    }

    fun resetResourceProcess() {
        _resourceProcessState.value = UpdateProcessState.Idle
    }

    private suspend fun downloadAndExtractResource(
        target: File,
        url: String,
        pack: ResourcePackSpec,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val downloadResult = resourceDownloader.downloadToTempFile(url) { progress ->
            _resourceProcessState.value = UpdateProcessState.Downloading(
                progress = progress.progress,
                speed = progress.speed,
                downloaded = progress.downloaded,
                total = progress.total
            )
        }

        val tempFile = downloadResult.getOrElse { e ->
            _resourceProcessState.value =
                UpdateProcessState.Failed(mapToUpdateError(e))
            return@withContext Result.failure(e)
        }

        var extractionStarted = false
        try {
            currentCoroutineContext().ensureActive()
            _resourceProcessState.value = UpdateProcessState.Verifying
            val extractResult = extractor.extract(
                zipFile = tempFile,
                destDir = target,
                pathFilter = pack::mapZipEntry,
                onProgress = { progress ->
                    _resourceProcessState.value = if (progress.phase == ZipExtractor.Phase.VERIFYING) {
                        UpdateProcessState.Verifying
                    } else {
                        extractionStarted = true
                        UpdateProcessState.Extracting(progress.progress, progress.current, progress.total)
                    }
                },
            )

            extractResult.fold(onSuccess = {
                currentCoroutineContext().ensureActive()
                _resourceProcessState.value = UpdateProcessState.Installing
                // Archive preservation, buffered copying and privileged delivery stay off the UI thread.
                if (pack.requiresPrivilegedDelivery) {
                    val keep = File(target.parentFile, MaaFiles.LAST_RESOURCE_UPDATE_ZIP)
                    runCatching { if (!tempFile.renameTo(keep)) tempFile.copyTo(keep, overwrite = true) }
                        .onFailure { Timber.w(it, "keep last resource update zip failed") }
                    coreDataPusher.pushHotUpdateIfNeeded()
                }
                _resourceProcessState.value = UpdateProcessState.Success
                Timber.i("Resource update completed: %s", pack.packId)
                Result.success(Unit)
            }, onFailure = { e ->
                // 解压中途失败时资源目录处于残缺状态，抹掉版本标记让下次重新触发完整更新
                pack.invalidateInstalledVersion(target)
                _resourceProcessState.value =
                    UpdateProcessState.Failed(
                        UpdateError.UnknownError(uiTextOf(R.string.update_error_extract_failed))
                    )
                Result.failure(e)
            })
        } catch (cancelled: CancellationException) {
            if (extractionStarted) pack.invalidateInstalledVersion(target)
            throw cancelled
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            _resourceProcessState.value = UpdateProcessState.Failed(mapToUpdateError(e))
            Result.failure(e)
        } finally {
            tempFile.delete()
        }
    }

    private fun failResource(error: UpdateError): Result<Unit> {
        _resourceProcessState.value = UpdateProcessState.Failed(error)
        return Result.failure(Exception(error.text.resolve(context)))
    }

    // ==================== 工具方法 ====================

    private fun mapToUpdateError(e: Throwable): UpdateError = when (e) {
        is AppUpdateSourceUnavailableException -> UpdateError.UnknownError(UiText.Dynamic(e.message.orEmpty()))
        is CdkRequiredException -> UpdateError.CdkRequired
        is LocalizedException -> UpdateError.UnknownError(e.uiText)
        is MirrorChyanBizException -> e.toUpdateError()
        else -> UpdateError.NetworkError(e.message)
    }

    private fun updateAchievementPayload(
        kind: String,
        source: UpdateSource,
        error: UpdateError? = null,
    ): Map<String, String> {
        val base = mutableMapOf("kind" to kind, "source" to source.name)
        if (source == UpdateSource.MIRROR_CHYAN && error.isCdkError()) {
            base["errorType"] = "CDK"
        }
        return base
    }

    private fun UpdateError?.isCdkError(): Boolean = when (this) {
        UpdateError.CdkRequired,
        MirrorchyanBizError.KeyExpired,
        MirrorchyanBizError.KeyInvalid,
        MirrorchyanBizError.ResourceQuotaExhausted,
        MirrorchyanBizError.KeyMismatched,
        MirrorchyanBizError.KeyBlocked -> true

        else -> false
    }

    private fun safeHost(url: String): String {
        if (url.isBlank()) return "<blank>"
        return runCatching { url.toUri().host ?: "<no-host>" }.getOrDefault("<invalid>")
    }
}
