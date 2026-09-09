package com.aliothmoon.maadroid.domain.service.update.checker

import com.aliothmoon.maadroid.common.i18n.UiText
import com.aliothmoon.maadroid.data.model.update.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ConfiguredAppVersionCheckerTest {
    @Test fun disabledConfigReturnsReasonWithoutCallingEitherChecker() = runBlocking {
        val github = RecordingChecker()
        val mirror = RecordingChecker()
        val result = ConfiguredAppVersionChecker(github, mirror, AppUpdateSourceConfig())
            .check("1.0.0", UpdateChannel.STABLE)
        assertTrue(result is UpdateCheckResult.Error)
        val text = (result as UpdateCheckResult.Error).error.text as UiText.Dynamic
        assertTrue(text.value.contains("maa.appUpdate.githubOwner"))
        assertTrue(github.calls.isEmpty())
        assertTrue(mirror.calls.isEmpty())
    }

    @Test fun absentOrInvalidRidUsesGithubForBothChannels() = runBlocking {
        for (rid in listOf("", "../old")) {
            val github = RecordingChecker()
            val mirror = RecordingChecker()
            val checker = ConfiguredAppVersionChecker(github, mirror, AppUpdateSourceConfig("example-org", "app", rid))
            for (channel in UpdateChannel.entries) checker.check("1.2.3", channel)
            assertEquals(UpdateChannel.entries.map { "1.2.3" to it }, github.calls)
            assertTrue(mirror.calls.isEmpty())
        }
    }

    @Test fun explicitRidUsesMirrorAndDoesNotFallBackOnItsError() = runBlocking {
        val github = RecordingChecker()
        val error = UpdateCheckResult.Error(UpdateError.NetworkError("offline"))
        val mirror = RecordingChecker(error)
        val checker = ConfiguredAppVersionChecker(github, mirror, AppUpdateSourceConfig("example-org", "app", "OwnApk"))
        assertSame(error, checker.check("1.2.3", UpdateChannel.BETA))
        assertEquals(listOf("1.2.3" to UpdateChannel.BETA), mirror.calls)
        assertTrue(github.calls.isEmpty())
    }

    private class RecordingChecker(
        val result: UpdateCheckResult = UpdateCheckResult.UpToDate("1.2.3"),
    ) : AppVersionChecker {
        val calls = mutableListOf<Pair<String, UpdateChannel>>()
        override suspend fun check(current: String, channel: UpdateChannel): UpdateCheckResult {
            calls += current to channel
            return result
        }
    }
}
