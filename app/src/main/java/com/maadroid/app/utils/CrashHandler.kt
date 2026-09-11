package com.maadroid.app.utils

import android.content.Context
import android.os.Process
import com.maadroid.app.data.config.MaaPathConfig
import com.maadroid.app.diagnostics.AppDiagnostics
import com.maadroid.app.diagnostics.DiagnosticCrashHandler
import kotlin.system.exitProcess

// Keep the existing Koin constructor, but crash storage never resolves Maa paths.
class CrashHandler(@Suppress("UNUSED_PARAMETER") pathConfig: MaaPathConfig) {
    fun init(context: Context) = installEarly(context)

    companion object {
        private var installed = false
        /** Safe before engine assembly, Koin, Timber or remote service startup. */
        fun installEarly(context: Context) {
            try {
                AppDiagnostics.initialize(context)
                synchronized(this) {
                    if (installed) return
                    val previous = Thread.getDefaultUncaughtExceptionHandler()
                    if (previous is DiagnosticCrashHandler) return
                    Thread.setDefaultUncaughtExceptionHandler(
                        DiagnosticCrashHandler(previous, AppDiagnostics::uncaught) {
                            Process.killProcess(Process.myPid())
                            exitProcess(1)
                        },
                    )
                    installed = true
                }
            } catch (_: Throwable) {
                // Leave the system handler in place if installation is unavailable.
            }
        }
    }
}
