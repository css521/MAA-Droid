package com.aliothmoon.maadroid.utils.log

import android.util.Log
import com.aliothmoon.maadroid.data.log.ApplicationLogWriter
import timber.log.Timber


class FileLogTree(
    private val writer: ApplicationLogWriter,
    private val isDebug: Boolean
) : Timber.DebugTree() {

    override fun isLoggable(tag: String?, priority: Int): Boolean {
        return if (isDebug) true else priority >= Log.WARN
    }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        writer.submit(priority, tag, message, t)
    }
}
