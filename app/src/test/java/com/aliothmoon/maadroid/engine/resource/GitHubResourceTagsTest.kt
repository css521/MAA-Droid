package com.aliothmoon.maadroid.engine.resource

import com.aliothmoon.maadroid.engine.ResourceRevision
import org.junit.Assert.*
import org.junit.Test

class GitHubResourceTagsTest {
    @Test fun sortsNumericallyAndIgnoresPrereleases() {
        val sha = "a".repeat(40)
        val body = listOf("v5.9.0", "v5.10.0", "v6.0.0-rc.1", "nightly", "v05.1.0")
            .joinToString(",", "[", "]") { """{"name":"$it","commit":{"sha":"$sha"}}""" }
        assertEquals("v5.10.0", GitHubResourceTags.latest(GitHubResourceTags.parse(body))!!.tag)
        assertEquals(0, GitHubResourceTags.compare("v5.0.0+build.1", "5.0.0"))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsStableTagWithoutFullSha() {
        GitHubResourceTags.parse("""[{"name":"v5.1.0","commit":{"sha":"abcd"}}]""")
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsTagMovedBetweenPages() {
        GitHubResourceTags.latest(listOf(ResourceRevision("v5.1.0", "a".repeat(40)), ResourceRevision("v5.1.0", "b".repeat(40))))
    }
    @Test fun noStableTagsReturnsNull() {
        assertNull(GitHubResourceTags.latest(GitHubResourceTags.parse("""[{"name":"v6.0.0-beta"}]""")))
    }
}
