package com.maadroid.app.utils.log

import com.maadroid.app.BuildConfig
import com.maadroid.app.data.log.ApplicationLogWriter
import com.maadroid.app.data.preferences.AppSettingsManager
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