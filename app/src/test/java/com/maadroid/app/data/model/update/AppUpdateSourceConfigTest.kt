package com.maadroid.app.data.model.update

import org.junit.Assert.*
import org.junit.Test

class AppUpdateSourceConfigTest {
    @Test fun missingOrPartialRepositoryDisablesEveryApkEndpointEvenWithRid() {
        for ((owner, repo) in listOf("" to "", "example-org" to "", "" to "independent-app")) {
            val config = AppUpdateSourceConfig(owner, repo, "independent-app")
            assertNotNull(config.disabledReason)
            assertFalse(config.usesMirrorChyan)
            assertUnavailable { config.githubReleasesUrl(false) }
            assertUnavailable { config.githubReleaseByTag("v1.0.0") }
            assertUnavailable { config.mirrorChyanResourceUrl() }
        }
    }

    @Test fun rejectsUrlsPathsAndControlCharactersInsteadOfChangingRepository() {
        for (owner in listOf("https://github.com/org", "org/repo", "org?x=y", "org\nother")) {
            assertNotNull(AppUpdateSourceConfig(owner, "app").disabledReason)
        }
        for (repo in listOf("../old", ".", "..", "app?x=y", "app#fragment", "app\\other")) {
            assertNotNull(AppUpdateSourceConfig("example-org", repo).disabledReason)
        }
    }

    @Test fun explicitRepositoryOwnsAllGithubUrlsAndTagsStayInOnePathSegment() {
        val config = AppUpdateSourceConfig(" example-org ", " independent-app ")
        val base = "https://api.github.com/repos/example-org/independent-app/releases"
        assertNull(config.disabledReason)
        assertEquals("$base/latest", config.githubReleasesUrl(false))
        assertEquals("$base?per_page=100", config.githubReleasesUrl(true))
        assertEquals("$base/tags/v1.2.3", config.githubReleaseByTag("v1.2.3"))
        assertEquals("$base/tags/release%2Fv1%3Ftest%23x%20y", config.githubReleaseByTag("release/v1?test#x y"))
        assertFalse(config.usesMirrorChyan)
        assertUnavailable { config.mirrorChyanResourceUrl() }
    }

    @Test fun mirrorRequiresItsOwnExplicitRidAndNeverDerivesItFromRepository() {
        val config = AppUpdateSourceConfig("example-org", "independent-app", " IndependentApk ")
        assertTrue(config.usesMirrorChyan)
        assertEquals("https://mirrorchyan.com/api/resources/IndependentApk/latest", config.mirrorChyanResourceUrl())
        for (rid in listOf("", " ", "../MAA-Droid", "https://mirrorchyan.com", "id?x=y", "..")) {
            val invalid = AppUpdateSourceConfig("example-org", "independent-app", rid)
            assertNull(invalid.disabledReason)
            assertFalse(invalid.usesMirrorChyan)
            assertUnavailable { invalid.mirrorChyanResourceUrl() }
        }
    }

    private fun assertUnavailable(block: () -> String) {
        val error = runCatching(block).exceptionOrNull()
        assertTrue(error is AppUpdateSourceUnavailableException)
        assertFalse(error!!.message.isNullOrBlank())
    }
}
