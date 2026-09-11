package com.maadroid.app.diagnostics

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import com.maadroid.app.BuildConfig
import java.io.File
import java.util.UUID

/** Offline breadcrumbs. Call only at important boundaries, with short non-sensitive facts. */
object AppDiagnostics {
    @Volatile private var store: DiagnosticStore? = null

    fun initialize(context: Context) {
        ignoreFailure {
            synchronized(this) {
                if (store != null) return@synchronized
                val identity = "version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) " +
                    "abi=${Build.SUPPORTED_ABIS.joinToString(",")} api=${Build.VERSION.SDK_INT} " +
                    "pid=${Process.myPid()} process=${DiagnosticText.field(Application.getProcessName())} " +
                    "session=${UUID.randomUUID()}"
                store = DiagnosticStore(File(context.filesDir, DiagnosticStore.DIRECTORY), identity)
                store?.record("application", "diagnostics_initialized")
            }
        }
    }

    fun record(component: String, phase: String, detail: String = "") {
        ignoreFailure { store?.record(component, phase, detail) }
    }

    fun failure(component: String, phase: String, error: Throwable) {
        ignoreFailure {
            store?.record(component, phase, "error=${error.javaClass.name}")
            store?.crash(component, phase, Thread.currentThread().name, error)
        }
    }

    internal fun uncaught(thread: Thread, error: Throwable) {
        ignoreFailure {
            store?.record("java", "uncaught_exception", "error=${error.javaClass.name}")
            store?.crash("java", "uncaught_exception", thread.name, error)
        }
    }

    internal fun snapshot(context: Context, destination: File): Boolean {
        initialize(context)
        return try { store?.snapshot(destination) == true } catch (_: Throwable) { false }
    }

    private inline fun ignoreFailure(block: () -> Unit) {
        try { block() } catch (_: Throwable) { /* May run before Koin, resources and Timber. */ }
    }
}
