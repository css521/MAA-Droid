package com.maadroid.app.engine

/** A tag resolved to an immutable upstream commit. */
data class ResourceRevision(val tag: String, val commit: String) {
    init {
        require(tag.isNotBlank())
        require(commit.matches(Regex("[0-9a-f]{40}"))) { "Expected a full Git commit" }
    }
}

/** Source archives are transport only: engines select and validate their own resource files. */
data class UpstreamArchive(
    val repository: String,
    val initialRevision: ResourceRevision,
    val resourcePrefix: String,
    val directories: List<String>,
    /** Source files inspected as text in staging only; never installed or executed. */
    val inspectionSuffixes: List<String> = emptyList(),
    /**
     * Release asset 下载 URL 模板。非空时 [archiveUrl] 用它替代 codeload。
     *
     * 占位符 `{tag}` 会被替换成 revision.tag。例如：
     * `"https://github.com/css521/MAA-Droid/releases/download/limbus-resource-{tag}/limbus-resource-{tag}.zip"`
     *
     * 用于从预打好的资源包下载（~43MB）而不是从上游整个仓库 zip（~100MB）。
     */
    val releaseAssetUrl: String? = null,
    /**
     * Tag 发现用的仓库。非空时 [tagsUrl] 用它替代 [repository]。
     * 用于从不同仓库发现资源 Release（CI 把资源包发在本仓库，而不是上游仓库）。
     */
    val tagsRepository: String? = null,
    /** Tag 前缀过滤。非空时只有匹配前缀的 tag 会被发现，版本比较时剥掉前缀。 */
    val tagPrefix: String = "",
) {
    init {
        require(repository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")))
        require(isSafeResourcePath(resourcePrefix))
        require(directories.isNotEmpty() && directories.all(::isSafeResourcePath))
        require(inspectionSuffixes.all { it.matches(Regex("\\.[A-Za-z0-9]+")) })
    }

    val tagsUrl: String get() {
        val repo = tagsRepository ?: repository
        return "https://api.github.com/repos/$repo/tags?per_page=100"
    }
    fun archiveUrl(revision: ResourceRevision): String {
        val template = releaseAssetUrl
        return if (template != null) template.replace("{tag}", revision.tag)
        else "https://codeload.github.com/$repository/zip/${revision.commit}"
    }

    fun mapEntry(name: String): String? {
        if (!isSafeResourcePath(name)) return null
        // GitHub codeload archives have exactly one directory above the source tree.
        val path = name.substringAfter('/', "")
        if (!path.startsWith("$resourcePrefix/")) return null
        val relative = path.removePrefix("$resourcePrefix/")
        if (inspectionSuffixes.any { relative.endsWith(it) }) return "$INSPECTION_DIRECTORY/$relative"
        return relative.takeIf { file -> directories.any { file.startsWith("$it/") } }
    }

    companion object {
        const val INSPECTION_DIRECTORY = ".upstream-source"
    }
}

fun isSafeResourcePath(path: String): Boolean = path.isNotBlank() &&
    !path.startsWith('/') && !path.contains('\\') && !path.contains(':') &&
    !path.contains('\u0000') && path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
