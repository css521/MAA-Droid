package com.aliothmoon.maadroid.domain.service.update.checker

import com.aliothmoon.maadroid.data.model.update.UpdateCheckResult

interface ResourceVersionChecker {
    suspend fun check(currentVersion: String): UpdateCheckResult
}
