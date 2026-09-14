package com.maadroid.app.engine.resource

import com.maadroid.app.engine.ResourceRevision
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
    /**
     * 第四段是本仓库的资源修订号：上游没发新版而我们的重打包产物变了时，靠它排先后。
     * `v5.0.0` 等价于 `v5.0.0.0`，所以已发布的三段 tag 不必改名就能继续比较。
     */
    @Test fun ordersTheFourthResourceRevisionSegment() {
        assertEquals(0, GitHubResourceTags.compare("v5.0.0", "v5.0.0.0"))
        assertTrue(GitHubResourceTags.compare("v5.0.0.1", "v5.0.0") > 0)
        assertTrue(GitHubResourceTags.compare("v5.0.0.2", "v5.0.0.1") > 0)
        assertTrue(GitHubResourceTags.compare("v5.0.0.10", "v5.0.0.9") > 0)
        // 上游发新版时，它必须压过同上游版本下的任何资源修订
        assertTrue(GitHubResourceTags.compare("v5.0.1", "v5.0.0.7") > 0)
    }

    @Test fun picksTheHighestResourceRevisionWithAPrefix() {
        val sha = "a".repeat(40)
        val body = listOf(
            "limbus-resource-v5.0.0", "limbus-resource-v5.0.0.2", "limbus-resource-v5.0.0.10",
            "v0.0.1", "limbus-resource-v4.11.5",
        ).joinToString(",", "[", "]") { """{"name":"$it","commit":{"sha":"$sha"}}""" }
        val revisions = GitHubResourceTags.parse(body, "limbus-resource-")
        assertEquals(4, revisions.size)   // v0.0.1 被前缀过滤掉
        assertEquals(
            "limbus-resource-v5.0.0.10",
            GitHubResourceTags.latest(revisions, "limbus-resource-")!!.tag,
        )
    }

    /** 第四段只接受数字，`-r1` 这类预发布形态仍然不许安装。 */
    @Test fun stillRejectsPrereleaseShapedRevisions() {
        assertNull(GitHubResourceTags.version("v5.0.0-r1"))
        assertNull(GitHubResourceTags.version("v5.0.0.1.2"))
        assertNull(GitHubResourceTags.version("v5.0.0.01"))
    }

    @Test fun noStableTagsReturnsNull() {
        assertNull(GitHubResourceTags.latest(GitHubResourceTags.parse("""[{"name":"v6.0.0-beta"}]""")))
    }
}
