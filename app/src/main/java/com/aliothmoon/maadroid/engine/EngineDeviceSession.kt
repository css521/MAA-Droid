package com.aliothmoon.maadroid.engine

import android.os.Binder
import android.os.IBinder
import android.view.Surface
import com.aliothmoon.maadroid.diagnostics.AppDiagnostics
import com.aliothmoon.maadroid.IEngineDeviceSession
import com.aliothmoon.maadroid.RemoteService
import com.aliothmoon.maadroid.domain.models.RunMode
import com.aliothmoon.maadroid.remote.BgrFrameLayout
import com.aliothmoon.maadroid.remote.PermissionGrantRequest
import com.aliothmoon.maadroid.remote.RemoteDeviceHandle
import com.aliothmoon.maadroid.remote.SetupResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One Android display/capture lifetime, independent of resource loading and task/UI state.
 * Acquire RemoteService through RemoteServiceManager.useRemoteService so the parent's
 * configured Root/Shizuku authorization and host permission grants remain in effect.
 * Stop/join engine work before close: a Frame's mapped bytes must have no remaining readers.
 */
class EngineDeviceSession private constructor(
    val device: RemoteDeviceHandle,
    val packageName: String,
    val displayId: Int,
    // Keep the App-process death token alive for the full lease lifetime.
    @Suppress("unused") private val owner: IBinder,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private var manualInput: ManualInput? = null

    /** A controller is tied to this lease and this particular preview, never a later run. */
    @Synchronized
    fun openManualInput(): ManualInput? {
        if (closed.get()) return null
        manualInput?.close()
        return ManualInput().also { manualInput = it }
    }

    @Synchronized
    fun releaseManualInput() {
        manualInput?.close()
    }

    /** Slots 0..7 belong to automation. Releasing a preview must never send touchCancel. */
    inner class ManualInput internal constructor() : AutoCloseable {
        private val contacts = mutableMapOf<Int, Pair<Int, Int>>()

        fun touchDown(x: Int, y: Int, contact: Int) = send {
            if (contact !in MANUAL_CONTACTS || contact in contacts) return@send
            // Keep even a partially delivered DOWN so failure cleanup attempts its UP.
            contacts[contact] = x to y
            device.input.touchDown(x, y, contact)
        }

        fun touchMove(x: Int, y: Int, contact: Int) = send {
            if (contact !in contacts) return@send
            contacts[contact] = x to y
            device.input.touchMove(x, y, contact)
        }

        fun touchUp(x: Int, y: Int, contact: Int) = send {
            if (contact !in contacts) return@send
            device.input.touchUp(x, y, contact)
            contacts.remove(contact)
        }

        private fun send(action: () -> Unit) = synchronized(this@EngineDeviceSession) {
            if (closed.get() || manualInput !== this) return@synchronized
            try {
                action()
            } catch (failure: Throwable) {
                if (!failure.isRecoverableEngineFailure()) throw failure
                Timber.w(failure, "Engine manual input failed")
                close()
            }
        }

        override fun close() = synchronized(this@EngineDeviceSession) {
            if (manualInput !== this) return@synchronized
            manualInput = null
            contacts.forEach { (contact, point) ->
                try {
                    device.input.touchUp(point.first, point.second, contact)
                } catch (failure: Throwable) {
                    if (!failure.isRecoverableEngineFailure()) throw failure
                    Timber.w(failure, "Engine manual contact release failed: %s", contact)
                }
            }
            contacts.clear()
        }
    }

    @Synchronized
    fun setPreviewSurface(surface: Surface?) {
        if (!closed.get()) device.setPreviewSurface(surface)
    }

    /** A failed connection may still own native work; release the display only after stop confirms. */
    suspend fun connect(engine: AutomationEngine): Result<Unit> {
        if (closed.get()) return Result.failure(IllegalStateException("设备会话已关闭"))
        return try {
            engine.connect(device).getOrThrow()
            Result.success(Unit)
        } catch (failure: Throwable) {
            releaseAfterFailure(engine, failure)
            if (failure is CancellationException) throw failure
            Result.failure(failure)
        }
    }

    /** Terminal stop. A later run must open a new device session and reconnect the engine. */
    suspend fun stop(engine: AutomationEngine): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        engine.stop().also { stopped -> if (stopped) close() }
    }

    @Synchronized
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try { releaseManualInput() } finally { device.close() }
        }
    }

    private suspend fun releaseAfterFailure(engine: AutomationEngine, failure: Throwable) {
        // Catch outside stop's context too, preserving the original connect/cancellation failure.
        try { stop(engine) } catch (cleanup: Throwable) {
            if (cleanup !== failure) failure.addSuppressed(cleanup)
        }
    }

    companion object {
        val MANUAL_CONTACTS: IntRange = 8..15

        /**
         * Select the first installed profile package, create capture, grant game permissions,
         * launch on that display, and wait for an actual, dimension-checked BGR frame.
         * Throws on failure; partial acquisition and cancellation always close the owned lease.
         * The timeout bounds first-frame polling, not blocking Android Binder transactions.
         */
        suspend fun open(
            profile: GameProfile,
            service: RemoteService,
            mode: RunMode,
            firstFrameTimeoutMs: Long = 10_000,
            framePollIntervalMs: Long = 50,
        ): EngineDeviceSession = open(
            profile, service, mode, firstFrameTimeoutMs, framePollIntervalMs,
            createOwner = { Binder() },
            createHandle = { remote, width, height, lease ->
                RemoteDeviceHandle(remote, width, height, lease, dpi = profile.display.dpi)
            },
        )

        internal suspend fun open(
            profile: GameProfile,
            service: RemoteService,
            mode: RunMode,
            firstFrameTimeoutMs: Long,
            framePollIntervalMs: Long,
            createOwner: () -> IBinder,
            createHandle: (RemoteService, Int, Int, IEngineDeviceSession) -> RemoteDeviceHandle,
        ): EngineDeviceSession {
            require(mode == RunMode.BACKGROUND) { "通用引擎暂不支持前台模式的输入，请使用后台模式" }
            require(firstFrameTimeoutMs > 0 && framePollIntervalMs > 0)
            val spec = profile.display
            require(spec.dpi > 0 && spec.width.toLong() * spec.height <= Int.MAX_VALUE / 3) {
                "无效显示规格: $spec"
            }
            var remote: IEngineDeviceSession? = null
            var session: EngineDeviceSession? = null
            try {
                return withContext(Dispatchers.IO) {
                    AppDiagnostics.record(profile.id, "device.setup")
                    val setup = service.setupDevice()
                    check(setup == SetupResult.OK) { "设备初始化失败: ${SetupResult.describe(setup)}" }
                    val pkg = profile.gamePackages.firstOrNull { service.isPackageInstalled(it) }
                        ?: error("未安装游戏，请安装以下包之一: ${profile.gamePackages.joinToString()}")
                    val owner = createOwner()
                    AppDiagnostics.record(profile.id, "device.display.create", "${spec.width}x${spec.height}@${spec.dpi}")
                    val lease = service.openDeviceSession(owner, mode.displayMode, spec.width, spec.height, spec.dpi)
                        ?: error("未能创建设备会话，请检查远程服务版本和显示状态")
                    remote = lease
                    val handle = createHandle(service, spec.width, spec.height, lease)
                    val ready = EngineDeviceSession(handle, pkg, lease.displayId, owner)
                    session = ready
                    // Same best-effort policy and permission mask as MaaCompositionService.
                    runCatching {
                        service.grantPermissions(PermissionGrantRequest(
                            packageName = pkg,
                            permissions = PermissionGrantRequest.PERM_BATTERY or PermissionGrantRequest.PERM_BACKGROUND,
                        ))
                    }.onFailure { Timber.w(it, "Failed to grant game battery/background permissions: %s", pkg) }
                    AppDiagnostics.record(profile.id, "device.game.launch", pkg)
                    check(handle.control.startApp(pkg)) { "启动游戏失败: $pkg (display=${ready.displayId})" }
                    AppDiagnostics.record(profile.id, "device.frame.wait")
                    val received = withTimeoutOrNull(firstFrameTimeoutMs) {
                        while (true) {
                            val frame = handle.frames.grab()
                            if (frame != null) {
                                BgrFrameLayout.byteCount(
                                    longArrayOf(frame.width.toLong(), frame.height.toLong(), frame.stride.toLong(), frame.seq),
                                    spec.width, spec.height, frame.buffer.remaining(),
                                )
                                break
                            }
                            delay(framePollIntervalMs)
                        }
                        true
                    }
                    check(received == true) { "等待游戏 BGR 首帧超时 (${firstFrameTimeoutMs}ms, ${spec.width}x${spec.height})" }
                    AppDiagnostics.record(profile.id, "device.frame.ready")
                    ready
                }
            } catch (failure: Throwable) {
                // Also covers cancellation at withContext's return boundary, after acquisition.
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { session?.close() ?: remote?.close() }
                        .exceptionOrNull()?.let(failure::addSuppressed)
                }
                throw failure
            }
        }
    }
}
