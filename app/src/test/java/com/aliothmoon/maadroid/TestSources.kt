package com.aliothmoon.maadroid

import java.io.File

/**
 * 契约测试的源码定位。
 *
 * 这些测试直接读源码文本来钉住约定（R8 keep 规则、i18n 硬编码、native 构建开关等），
 * 因此需要在**任意工作目录**下都能找到某个模块里的文件：Gradle 跑测试时 cwd 随模块而变，
 * 从 IDE 单跑又可能是仓库根。
 *
 * 两条设计取舍：
 *
 * 1. **不写死模块目录名**。模块路径会随分层调整而变（`core-bridge` → `core/bridge`），
 *    写死清单意味着每次都要回来改，漏改时报的是 "source not found" 而不是失败在被测约定上，
 *    很误导。改为先找仓库根（含 `settings.gradle.kts`），再枚举含 `build.gradle.kts`
 *    的目录作为模块。
 * 2. **路径歧义直接失败**，而不是挑一个返回。`build.gradle.kts` 这类文件每个模块都有，
 *    静默挑中错的那个会让测试**读着无关文件通过** —— 比找不到危险得多（实测踩过：
 *    native 开关的用例本该读 core/bridge 的构建文件，却读到了 app 的）。
 *    需要指定模块时用 [inApp] 或 [inModuleOwning]。
 */
internal object TestSources {

    /**
     * 定位仓库内唯一匹配该相对路径的文件。
     *
     * 多个模块都有该路径时抛异常并列出候选 —— 此时调用方应改用 [inApp] 或
     * [inModuleOwning] 明确意图。
     */
    fun resolve(relativePath: String): File =
        unique(candidates(relativePath).filter { it.isFile }, relativePath, "source")

    fun resolveDir(relativePath: String): File =
        unique(candidates(relativePath).filter { it.isDirectory }, relativePath, "dir")

    /** 找不到返回 null，供「该文件可有可无」的用例自行判断 */
    fun findOrNull(relativePath: String): File? =
        candidates(relativePath).firstOrNull { it.isFile }

    /** 明确在宿主模块 `app/` 下定位 */
    fun inApp(relativePath: String): File = inModule("app", relativePath)

    /**
     * 在**拥有某项能力**的那个模块下定位。
     *
     * 例如 native 构建开关归 `core/bridge`，但测试不该写死这个名字 ——
     * 它关心的是「谁拥有 `src/main/native/CMakeLists.txt`」。这样 native 代码
     * 将来再挪模块，用例无需改动。
     */
    fun inModuleOwning(markerRelativePath: String, relativePath: String): File {
        val root = repoRoot() ?: error("未找到仓库根（cwd=${File(".").absolutePath}）")
        val owners = moduleDirs(root).filter { File(it, markerRelativePath).exists() }
        val owner = when (owners.size) {
            1 -> owners.single()
            0 -> error("没有模块拥有标志文件 $markerRelativePath")
            else -> error(
                "多个模块都拥有 $markerRelativePath：${owners.map { it.name }}，" +
                    "标志文件不足以唯一确定模块"
            )
        }
        val file = File(owner, relativePath)
        check(file.exists()) { "${owner.name} 下不存在 $relativePath" }
        return file
    }

    private fun inModule(moduleDirName: String, relativePath: String): File {
        val root = repoRoot() ?: error("未找到仓库根（cwd=${File(".").absolutePath}）")
        val file = File(File(root, moduleDirName), relativePath)
        check(file.exists()) { "$moduleDirName 下不存在 $relativePath" }
        return file
    }

    private fun unique(found: List<File>, relativePath: String, what: String): File {
        // 同一文件可能经不同候选路径命中，按真实路径去重
        val distinct = found.distinctBy { it.canonicalPath }
        return when (distinct.size) {
            1 -> distinct.single()
            0 -> error(
                "$what not found: $relativePath (cwd=${File(".").absolutePath}, " +
                    "repoRoot=${repoRoot()?.path ?: "未找到"})"
            )
            else -> error(
                "$relativePath 在多个模块下都存在：" +
                    distinct.joinToString { describe(it) } +
                    "。静默挑一个会让用例读到无关文件，请改用 inApp() 或 inModuleOwning() 明确模块。"
            )
        }
    }

    /** 用相对仓库根的路径描述候选，比父目录名可辨（父目录常是同名的包目录） */
    private fun describe(file: File): String {
        val root = repoRoot() ?: return file.path
        return runCatching { file.relativeTo(root).path }.getOrDefault(file.path)
    }

    private fun candidates(relativePath: String): List<File> = buildList {
        val root = repoRoot()
        if (root == null) {
            add(File(relativePath))
            return@buildList
        }
        add(File(root, relativePath))
        for (module in moduleDirs(root)) {
            add(File(module, relativePath))
        }
    }

    /** 从 cwd 向上找含 settings.gradle.kts 的目录 */
    private fun repoRoot(): File? {
        var dir: File? = File(".").absoluteFile
        var hops = 0
        while (dir != null && hops++ < MAX_HOPS) {
            if (File(dir, SETTINGS_FILE).isFile) return dir
            dir = dir.parentFile
        }
        return null
    }

    /**
     * 仓库内的模块目录：含 `build.gradle.kts` 的目录。
     *
     * 只下探有限层数并跳过 `build` 与隐藏目录 —— 构建产物里有大量源码拷贝，
     * 扫进来会让定位取到过期文件，这比找不到更难查。
     */
    private fun moduleDirs(root: File): List<File> {
        val found = mutableListOf<File>()
        fun scan(dir: File, depth: Int) {
            if (depth > MAX_MODULE_DEPTH) return
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (!child.isDirectory) continue
                if (child.name.startsWith(".") || child.name in SKIP_DIRS) continue
                if (File(child, BUILD_FILE).isFile) found += child
                scan(child, depth + 1)
            }
        }
        scan(root, 1)
        return found
    }

    private const val SETTINGS_FILE = "settings.gradle.kts"
    private const val BUILD_FILE = "build.gradle.kts"
    private const val MAX_HOPS = 8

    /** app / engine/limbus / tooling/ksp-processor 最深两层即可 */
    private const val MAX_MODULE_DEPTH = 2

    private val SKIP_DIRS = setOf("build", "gradle", "docs", "scripts", "src")
}
