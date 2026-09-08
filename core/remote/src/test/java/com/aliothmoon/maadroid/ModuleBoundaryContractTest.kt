package com.aliothmoon.maadroid

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模块边界契约：依赖方向必须严格单向。
 *
 * ```
 * app → engine:<游戏> → engine:api → core:* → hidden-api
 * ```
 *
 * 「一个 App 控多个游戏」以及「加第三个游戏只是加一个模块」这两件事，全都建立在
 * 这条单向性上。而倒挂只需要一次「顺手 import」就会发生，且发生时不会有任何报错 ——
 * 只是下一个游戏再也接不进来。所以用测试钉住。
 *
 * 模块目录**动态发现**而非写死名字：分层调整（`core-bridge` → `core/bridge`）与
 * 新增引擎都不该要求回来改这份清单。更要紧的是原先写死名字的版本在找不到目录时会
 * `continue` 跳过 —— 表现为「测试通过」而其实一个文件都没检查，这比报错危险得多。
 * 现在找不到就直接失败。
 */
class ModuleBoundaryContractTest {

    /**
     * 契约所在包。`engine:api` 的类型就住在这一层（`engine.GameProfile` 等），
     * 而具体引擎住在它的**子包**（`engine.limbus.*`）。
     *
     * 这个区分很要紧：`core:remote` 依赖契约是**设计如此**
     * （`RemoteDeviceHandle` 要实现 `engine.DeviceHandle`），
     * 禁的只能是具体引擎的子包，不能是整个 `engine.` 前缀。
     */
    private val contractPackage = "com.aliothmoon.maadroid.engine"

    /** 具体引擎的包前缀，由 `engine/<名字>` 目录名推导（api 除外） */
    private fun concreteEnginePackages(): List<String> =
        modulesUnder("engine")
            .filterNot { it.first == ENGINE_API_MODULE }
            .map { "$contractPackage.${it.first}" }

    /** 宿主应用层的包前缀 —— 引擎与 core 都不得依赖 */
    private val hostPackages = listOf(
        "com.aliothmoon.maadroid.presentation.",
        "com.aliothmoon.maadroid.koin.",
        "com.aliothmoon.maadroid.schedule.",
        "com.aliothmoon.maadroid.announcement.",
        "com.aliothmoon.maadroid.data.repository",
        "com.aliothmoon.maadroid.data.resource",
        "com.aliothmoon.maadroid.data.achievement",
    )

    /** 方舟逻辑目前仍在 `:app`（尚未抽成 engine/arknights），其包前缀同样禁止 */
    private val arknightsInApp = "com.aliothmoon.maadroid.maa."

    @Test
    fun coreModulesDependOnNeitherEnginesNorHost() {
        val cores = modulesUnder("core")
        assertTrue("未发现任何 core 模块，契约测试形同虚设", cores.isNotEmpty())

        val forbidden = hostPackages + arknightsInApp + concreteEnginePackages()
        val offenders = cores.flatMap { (name, src) -> importsMatching(name, src, forbidden) }

        assertTrue(
            "core:* 依赖了引擎或宿主，多引擎架构会失效：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun enginesDoNotDependOnEachOther() {
        val engines = modulesUnder("engine").filterNot { it.first == ENGINE_API_MODULE }
        assertTrue("未发现任何游戏引擎模块", engines.isNotEmpty())

        val offenders = engines.flatMap { (name, src) ->
            // 只禁其它引擎的包，不禁自己的
            val others = engines.map { it.first }.filterNot { it == name }
                .map { "$contractPackage.$it" }
            if (others.isEmpty()) emptyList() else importsMatching(name, src, others)
        }

        assertTrue(
            "引擎之间出现了互相依赖，会让它们无法独立增删：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /**
     * 引擎不得依赖宿主应用层。
     *
     * 这条现在对 `engine/limbus` 成立，而它同时**编码了抽取 `engine/arknights` 的前置条件**：
     * 方舟代码现在用着仍在 `:app` 里的偏好、通知、Compose 组件，直接搬过去会立刻触犯这条。
     * 也就是说必须先把这些通用能力下沉到 `core:common` / `core:ui`，顺序不能颠倒。
     */
    @Test
    fun enginesDoNotDependOnHostAppLayer() {
        val engines = modulesUnder("engine")
        assertTrue("未发现任何 engine 模块", engines.isNotEmpty())

        val offenders = engines.flatMap { (name, src) ->
            importsMatching(name, src, hostPackages + arknightsInApp)
        }

        assertTrue(
            "引擎依赖了宿主应用层，说明该能力应先下沉到 core:*：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /** 契约模块不得认识任何具体引擎，否则它就不再是契约 */
    @Test
    fun engineApiKnowsNoConcreteEngine() {
        val api = modulesUnder("engine").firstOrNull { it.first == ENGINE_API_MODULE }
        assertTrue("未找到 engine:api 模块", api != null)

        val offenders = importsMatching(
            ENGINE_API_MODULE, api!!.second, concreteEnginePackages() + arknightsInApp,
        )

        assertTrue(
            "engine:api 引用了具体引擎，契约将不再通用：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    // ---- 以下为目录发现与扫描 ----

    /** `<group>/<模块名>` 形式的模块及其 main 源码目录 */
    private fun modulesUnder(group: String): List<Pair<String, File>> {
        val root = repoRoot() ?: return emptyList()
        val dir = File(root, group)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            .orEmpty()
            .filter { it.isDirectory && File(it, "build.gradle.kts").isFile }
            .mapNotNull { module ->
                val src = File(module, "src/main")
                if (src.isDirectory) module.name to src else null
            }
            .sortedBy { it.first }
    }

    private fun importsMatching(
        module: String,
        src: File,
        forbidden: List<String>,
    ): List<String> {
        val hits = mutableListOf<String>()
        src.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .forEach { file ->
                file.readLines().forEachIndexed { i, line ->
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("import ")) return@forEachIndexed
                    forbidden.firstOrNull { trimmed.contains(it) }?.let { bad ->
                        hits += "$module/${file.name}:${i + 1} → $bad"
                    }
                }
            }
        return hits
    }

    private companion object {
        const val ENGINE_API_MODULE = "api"
    }

    /** 从 cwd 向上找含 settings.gradle.kts 的目录；测试的 cwd 随模块而变 */
    private fun repoRoot(): File? {
        var dir: File? = File(".").absoluteFile
        var hops = 0
        while (dir != null && hops++ < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        return null
    }
}
