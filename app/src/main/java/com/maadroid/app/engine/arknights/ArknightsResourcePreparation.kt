package com.maadroid.app.engine.arknights

import com.maadroid.app.data.config.MaaPathConfig
import com.maadroid.app.data.resource.ActivityManager
import com.maadroid.app.domain.service.CoreDataPusher
import com.maadroid.app.domain.service.MaaResourceLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.file.Files

/** Adapts the existing loader; profile switches, remote setup and overlays stay there. */
class ArknightsResourcePreparation(
    private val resourceLoader: MaaResourceLoader,
    private val pathConfig: MaaPathConfig,
    private val coreDataPusher: CoreDataPusher,
    private val activityManager: ActivityManager,
) : MaaResourcePreparation {
    override suspend fun prepare(resourceDir: File, options: MaaRunOptions): Result<Unit> = try {
        currentCoroutineContext().ensureActive()
        val expected = File(pathConfig.cacheResourceDir)
        require(resourceDir.isDirectory && expected.isDirectory && Files.isSameFile(resourceDir.toPath(), expected.toPath())) {
            "方舟资源目录不匹配或尚未就绪：期望 ${expected.path}，实际 ${resourceDir.path}"
        }
        currentCoroutineContext().ensureActive()
        activityManager.runIfDirty {
            currentCoroutineContext().ensureActive()
            // Throw before runIfDirty clears its flag; a failed reload must remain retryable.
            resourceLoader.load(options.clientType).getOrThrow()
            currentCoroutineContext().ensureActive()
        }
        currentCoroutineContext().ensureActive()
        resourceLoader.ensureLoaded(options.clientType).getOrThrow()
        currentCoroutineContext().ensureActive()
        check(coreDataPusher.pushUserData()) { "方舟用户数据投递失败" }
        currentCoroutineContext().ensureActive()
        // deployWithPause is a connection option, handled by the engine after preparation.
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        // Existing dependencies may turn cancellation into Result.failure or false.
        currentCoroutineContext().ensureActive()
        Result.failure(failure)
    }
}
