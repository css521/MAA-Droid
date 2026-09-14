package com.maadroid.app.engine.resource

import com.maadroid.app.engine.ResourceRevision
import java.math.BigInteger
import kotlinx.serialization.json.*

/** GitHub ordering is not semver ordering. Prereleases and malformed stable SHAs are never installed. */
internal object GitHubResourceTags {
    private val stable = Regex("^v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:\\.(0|[1-9][0-9]*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$")

    /**
     * 解析成**四段**。前三段是上游版本，第四段是本仓库的资源修订号（缺省 0）。
     *
     * 为什么需要第四段：资源包 tag 跟着上游命名（`limbus-resource-v5.0.0` ← LALC v5.0.0），
     * 但重打包的内容还取决于我们自己的打包脚本与动作白名单。上游不发新版而我们的产物变了时，
     * 原来无路可走 —— 同名 tag 不会重发（流水线见 tag 存在即跳过），而 `+build` 元数据
     * 按 semver 规则不参与比较，比出来是相等。第四段让这种"资源侧修订"能被排出先后。
     *
     * `v5.0.0` 与 `v5.0.0.0` 因此等价，旧 tag 无需改名即可继续参与比较。
     */
    fun version(tag: String): List<BigInteger>? = stable.matchEntire(tag)?.let { match ->
        (1..4).map { i ->
            match.groupValues[i].takeIf(String::isNotEmpty)?.let(::BigInteger) ?: BigInteger.ZERO
        }
    }

    fun compare(a: String, b: String, tagPrefix: String = ""): Int {
        val left = requireNotNull(version(a.removePrefix(tagPrefix))) { "Invalid stable tag: $a" }
        val right = requireNotNull(version(b.removePrefix(tagPrefix))) { "Invalid stable tag: $b" }
        return left.zip(right).firstNotNullOfOrNull { (x, y) -> x.compareTo(y).takeIf { it != 0 } } ?: 0
    }

    fun parse(body: String, tagPrefix: String = ""): List<ResourceRevision> = Json.parseToJsonElement(body).jsonArray.mapNotNull { item ->
        val obj = item.jsonObject
        val name = (obj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
        // 前缀过滤：只看匹配的 tag（例如 limbus-resource-v5.0.0），其他的跳过
        if (tagPrefix.isNotEmpty() && !name.startsWith(tagPrefix)) return@mapNotNull null
        val versionPart = name.removePrefix(tagPrefix)
        if (version(versionPart) == null) return@mapNotNull null
        val sha = obj.getValue("commit").jsonObject.getValue("sha").jsonPrimitive.content.lowercase()
        ResourceRevision(name, sha)  // 保留原始 tag 名（含前缀），archiveUrl 需要它
    }

    fun latest(revisions: List<ResourceRevision>, tagPrefix: String = ""): ResourceRevision? {
        revisions.groupBy { it.tag }.forEach { (tag, refs) ->
            require(refs.map { it.commit }.distinct().size == 1) { "Tag changed during update check: $tag" }
        }
        val latest = revisions.maxWithOrNull { a, b -> compare(a.tag, b.tag, tagPrefix) } ?: return null
        require(revisions.filter { compare(it.tag, latest.tag, tagPrefix) == 0 }.map { it.commit }.distinct().size == 1) {
            "Ambiguous commits for stable version ${latest.tag}"
        }
        return latest
    }
}
