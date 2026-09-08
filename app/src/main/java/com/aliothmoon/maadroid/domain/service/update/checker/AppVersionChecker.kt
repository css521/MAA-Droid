package com.aliothmoon.maadroid.domain.service.update.checker

import com.aliothmoon.maadroid.data.model.update.UpdateChannel
import com.aliothmoon.maadroid.data.model.update.UpdateCheckResult

interface AppVersionChecker {
    suspend fun check(current: String, channel: UpdateChannel): UpdateCheckResult
}
