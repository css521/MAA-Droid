package com.aliothmoon.maadroid.utils.log

import com.aliothmoon.maadroid.BuildConfig
import com.aliothmoon.maadroid.data.log.ApplicationLogWriter
import com.aliothmoon.maadroid.data.preferences.AppSettingsManager
import timber.log.Timber

class LogTreeHolder(
    private val writer: ApplicationLogWriter,
    private val appSettings: AppSettingsManager
) {
    fun getTrees(): Array<Timber.Tree> {
        return arrayOf(
            if (BuildConfig.DEBUG) {
                DebugTree()
            } else {
                ReleaseTree()
            },
            FileLogTree(writer, appSettings.debugMode.value)
        )
    }

    fun setup() {
        getTrees().forEach {
            Timber.plant(it)
        }
    }
}