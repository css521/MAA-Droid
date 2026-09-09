package com.aliothmoon.maadroid.engine

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
) {
    init {
        require(repository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")))
        require(isSafeResourcePath(resourcePrefix))
        require(directories.isNotEmpty() && directories.all(::isSafeResourcePath))
        require(inspectionSuffixes.all { it.matches(Regex("\\.[A-Za-z0-9]+")) })
    }

    val tagsUrl: String get() = "https://api.github.com/repos/$repository/tags?per_page=100"
    fun archiveUrl(revision: ResourceRevision): String =
        "https://codeload.github.com/$repository/zip/${revision.commit}"

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
