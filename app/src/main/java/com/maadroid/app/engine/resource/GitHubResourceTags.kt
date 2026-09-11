package com.maadroid.app.engine.resource

import com.maadroid.app.engine.ResourceRevision
import java.math.BigInteger
import kotlinx.serialization.json.*

/** GitHub ordering is not semver ordering. Prereleases and malformed stable SHAs are never installed. */
internal object GitHubResourceTags {
    private val stable = Regex("^v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$")
    fun version(tag: String): List<BigInteger>? = stable.matchEntire(tag)?.groupValues
        ?.slice(1..3)?.map(::BigInteger)

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
