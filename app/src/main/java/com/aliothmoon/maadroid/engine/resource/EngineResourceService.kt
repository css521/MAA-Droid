package com.aliothmoon.maadroid.engine.resource

import android.content.Context
import com.aliothmoon.maadroid.data.api.HttpClientHelper
import com.aliothmoon.maadroid.data.datasource.DownloadProgress
import com.aliothmoon.maadroid.data.datasource.ResourceDownloader
import com.aliothmoon.maadroid.engine.ResourcePackSpec
import com.aliothmoon.maadroid.engine.ResourceRevision
import com.aliothmoon.maadroid.engine.isSafeResourcePath
import com.aliothmoon.maadroid.remote.EngineDataRoot
import java.io.File
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/** Register as a singleton. Session code must use the same ResourcePackLocks lease while running. */
class EngineResourceService internal constructor(
    private val dataRoot: File,
    private val transport: ResourceTransport,
    private val installer: AtomicResourceInstaller = AtomicResourceInstaller(),
) {
    constructor(context: Context, httpClient: HttpClientHelper, downloader: ResourceDownloader) :
        this(EngineDataRoot.of(context.applicationContext), HttpResourceTransport(httpClient, downloader))

    private val flows = ConcurrentHashMap<String, MutableStateFlow<EngineResourceState>>()
    fun state(pack: ResourcePackSpec): StateFlow<EngineResourceState> = mutableState(pack).asStateFlow()
    private fun mutableState(pack: ResourcePackSpec) = flows.getOrPut(pack.packId) { MutableStateFlow(EngineResourceState()) }

    /** Offline when an intact installation exists. First install uses the pinned commit, never the API. */
    suspend fun ensureInstalled(pack: ResourcePackSpec): Result<File> = operate(pack, waitForLock = true) { target ->
        if (pack.readInstalledVersion(target) != null && pack.verifyInstalledFiles(target) == null) {
            ready(pack, target)
        } else {
            install(pack, target, readRevision(pack, target) ?: requireNotNull(pack.upstreamArchive).initialRevision)
        }
        target
    }

    /** Read/verify local files for initial UI state. No network, recovery, installation, or file writes. */
    suspend fun refreshInstalled(pack: ResourcePackSpec): Result<EngineResourceState> =
        operate(pack, recover = false) { target ->
            if (pack.readInstalledVersion(target) == null) {
                mutableState(pack).value = EngineResourceState()
            } else {
                pack.verifyInstalledFiles(target)?.let { throw IOException(it) }
                ready(pack, target)
            }
            mutableState(pack).value
        }

    /** Does not download or replace resources. 403/rate limiting is surfaced as FAILED, with old version retained. */
    suspend fun checkForUpdate(pack: ResourcePackSpec): Result<ResourceRevision?> = operate(pack) { target ->
        val latest = findUpdate(pack, target)
        mutableState(pack).update { it.copy(phase = if (it.installedVersion == null) ResourcePhase.NOT_INSTALLED else ResourcePhase.READY, availableRevision = latest) }
        latest
    }

    /** Resolve tags to an immutable SHA, then validate and replace under the pack lease. */
    suspend fun update(pack: ResourcePackSpec): Result<File> = operate(pack) { target ->
        val revision = findUpdate(pack, target)
        if (revision != null) install(pack, target, revision)
        else if (pack.readInstalledVersion(target) == null || pack.verifyInstalledFiles(target) != null) {
            install(pack, target, requireNotNull(pack.upstreamArchive).initialRevision)
        } else ready(pack, target)
        target
    }

    private suspend fun <T> operate(
        pack: ResourcePackSpec,
        waitForLock: Boolean = false,
        recover: Boolean = true,
        block: suspend (File) -> T,
    ): Result<T> = withContext(Dispatchers.IO) {
        val state = mutableState(pack)
        var lease: Closeable? = null
        try {
            requireNotNull(pack.upstreamArchive) { "${pack.packId} 使用既有资源更新服务" }
            require(isSafeResourcePath(pack.relativeRoot)) { "Unsafe resource root" }
            val root = dataRoot.canonicalFile
            val target = File(root, pack.relativeRoot)
            require(target.canonicalPath.startsWith(root.path + File.separator)) { "Resource root escapes app data" }
            lease = if (waitForLock) ResourcePackLocks.acquire(pack.packId)
                else ResourcePackLocks.tryAcquire(pack.packId) ?: throw ResourcePackBusyException(pack.packId)
            state.update { it.copy(phase = ResourcePhase.CHECKING, error = null, download = null) }
            if (recover) installer.recover(target)
            val installed = pack.readInstalledVersion(target)
            state.update { it.copy(installedVersion = installed, installedRevision = readRevision(pack, target)) }
            Result.success(block(target))
        } catch (cancelled: CancellationException) {
            state.update { it.copy(phase = ResourcePhase.FAILED, error = "资源操作已取消", download = null) }
            throw cancelled
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            state.update { it.copy(phase = ResourcePhase.FAILED, error = e.message ?: "资源操作失败", download = null) }
            Result.failure(e)
        } finally {
            lease?.close()
        }
    }

    private suspend fun findUpdate(pack: ResourcePackSpec, target: File): ResourceRevision? {
        val source = requireNotNull(pack.upstreamArchive)
        mutableState(pack).update { it.copy(phase = ResourcePhase.CHECKING, availableRevision = null) }
        val revisions = mutableListOf<ResourceRevision>()
        var page = 1
        while (true) {
            val result = transport.tags("${source.tagsUrl}&page=$page")
            revisions += GitHubResourceTags.parse(result.body)
            if (!result.hasNext) break
            check(++page <= 20) { "上游标签页数超出限制，无法确认最新稳定版本" }
        }
        val latest = GitHubResourceTags.latest(revisions) ?: error("上游没有可用的稳定语义版本标签")
        val current = readRevision(pack, target) ?: source.initialRevision
        val compared = GitHubResourceTags.compare(latest.tag, current.tag)
        require(compared != 0 || latest.commit == current.commit) { "上游稳定标签的 SHA 已改变，拒绝覆盖已固定的提交" }
        return latest.takeIf { compared > 0 }
    }

    private suspend fun install(pack: ResourcePackSpec, target: File, revision: ResourceRevision) {
        val state = mutableState(pack)
        state.update { it.copy(phase = ResourcePhase.DOWNLOADING, availableRevision = revision, download = null) }
        val archive = transport.download(requireNotNull(pack.upstreamArchive).archiveUrl(revision)) { progress ->
            state.update { it.copy(download = progress) }
        }
        try {
            val context = currentCoroutineContext()
            state.update { it.copy(phase = ResourcePhase.INSTALLING, filesInstalled = 0, filesTotal = 0) }
            installer.install(archive, target, pack, revision, ensureActive = { context.ensureActive() }) { done, total ->
                state.update { it.copy(filesInstalled = done, filesTotal = total) }
            }
            ready(pack, target)
        } finally {
            archive.delete()
        }
    }

    private fun ready(pack: ResourcePackSpec, target: File) {
        mutableState(pack).value = EngineResourceState(
            phase = ResourcePhase.READY,
            installedVersion = pack.readInstalledVersion(target),
            installedRevision = readRevision(pack, target),
        )
    }

    private fun readRevision(pack: ResourcePackSpec, target: File): ResourceRevision? = runCatching {
        val upstream = Json.parseToJsonElement(File(target, "manifest.json").readText()).jsonObject.getValue("upstream").jsonObject
        require(upstream.getValue("repo").jsonPrimitive.content == requireNotNull(pack.upstreamArchive).repository)
        ResourceRevision(upstream.getValue("tag").jsonPrimitive.content, upstream.getValue("commit").jsonPrimitive.content)
    }.getOrNull()
}

class ResourcePackBusyException(val packId: String) : IOException("资源包 $packId 正在使用中，请结束任务后重试")

enum class ResourcePhase { NOT_INSTALLED, CHECKING, DOWNLOADING, INSTALLING, READY, FAILED }

data class EngineResourceState(
    val phase: ResourcePhase = ResourcePhase.NOT_INSTALLED,
    val installedVersion: String? = null,
    val installedRevision: ResourceRevision? = null,
    val availableRevision: ResourceRevision? = null,
    val download: DownloadProgress? = null,
    val filesInstalled: Int = 0,
    val filesTotal: Int = 0,
    val error: String? = null,
)

internal data class ResourceTagPage(val body: String, val hasNext: Boolean = false)
internal interface ResourceTransport {
    suspend fun tags(url: String): ResourceTagPage
    suspend fun download(url: String, progress: (DownloadProgress) -> Unit): File
}

private class HttpResourceTransport(
    private val http: HttpClientHelper,
    private val downloader: ResourceDownloader,
) : ResourceTransport {
    override suspend fun tags(url: String): ResourceTagPage = http.get(url, headers = mapOf(
        "Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28",
    )).use { response ->
        if (!response.isSuccessful) {
            val reason = if (response.code == 403 || response.code == 429) "GitHub 匿名访问受限，请稍后重试" else "GitHub 标签请求失败"
            throw IOException("$reason（HTTP ${response.code}）")
        }
        val body = response.body.charStream().use { reader ->
            val text = StringBuilder()
            val buffer = CharArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = reader.read(buffer)
                if (count < 0) break
                require(text.length + count <= 2 * 1024 * 1024) { "GitHub 标签响应过大" }
                text.append(buffer, 0, count)
            }
            text.toString()
        }
        ResourceTagPage(body, response.headers("Link").any { it.contains("rel=\"next\"") })
    }

    override suspend fun download(url: String, progress: (DownloadProgress) -> Unit): File =
        downloader.downloadToTempFile(url, progress).getOrThrow()
}
