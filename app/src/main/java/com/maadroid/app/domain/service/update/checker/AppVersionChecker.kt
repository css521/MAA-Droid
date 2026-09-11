package com.maadroid.app.domain.service.update.checker

import com.maadroid.app.data.model.update.UpdateChannel
import com.maadroid.app.data.model.update.UpdateCheckResult

interface AppVersionChecker {
    suspend fun check(current: String, channel: UpdateChannel): UpdateCheckResult
}
