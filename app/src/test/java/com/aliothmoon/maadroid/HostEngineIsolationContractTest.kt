package com.aliothmoon.maadroid

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 宿主与方舟的耦合度棘轮。
 *
 * `ModuleBoundaryContractTest` 只扫 `core/` 与 `engine/`，**从不扫 `app`**。而方舟逻辑
 * 目前整块住在 `:app` 里，两个方向的耦合都不受任何约束：
 *
 * - 宿主 → 方舟：抽出 `engine/arknights` 时要逐条拆掉的东西
 * - 方舟 → 宿主：决定必须先建哪些 `core:*` 模块（引擎不得依赖宿主，见
 *   `ModuleBoundaryContractTest.enginesDoNotDependOnHostAppLayer`）
 *
 * 一刀切禁止现在会红几百条，只能被注释掉，等于没有。所以这里做**精确计数棘轮**：
 * 数字对不上就失败，**降下来也要失败**，逼着修改者把新数字写进来。
 *
 * 最后一条是刻意的。基线若允许「实际更少但不更新」，它会慢慢失真成一个谁也不敢动的
 * 大数字；要求同步更新，等于让每次清理都在表里留下可见进度。失败信息直接打印可粘贴的
 * 新表，更新成本只有一次复制。
 *
 * 所有数字均由本测试实测得出，不是估计。
 */
class HostEngineIsolationContractTest {

    // ------------------------------------------------------------------
    // 方向一：宿主 → 方舟
    // ------------------------------------------------------------------

    /**
     * 整包即方舟的前缀。
     *
     * 三个包**刻意不在此列**，因为它们混杂，整包禁会把宿主功能误判成方舟：
     * - `data.model.`：`update/`（App 自更新，17 个宿主文件在用）与 `Log*.kt`
     *   （全宿主日志模型，19 处引用）住在里面
     * - `domain.state.`：`ResourceInitState`（宿主资源初始化，9 个文件在用）
     * - `data.config.`：`ResourceVersionHelper`（纯版本字符串工具，无方舟概念）
     *
     * 后两个包里真正属于方舟的类见 [arknightsClasses]。`domain.enums.` 则确认整包
     * 都是方舟（基建/肉鸽枚举，含 `UiUsageConstants` 的基建预设）。
     */
    private val arknightsPackages = listOf(
        "com.aliothmoon.maadroid.maa.",
        "com.aliothmoon.maadroid.data.resource.",
        "com.aliothmoon.maadroid.data.achievement.",
        "com.aliothmoon.maadroid.data.repository.",
        "com.aliothmoon.maadroid.presentation.view.panel.",
        // 已抽成 engine:arknights 模块的部分：宿主引用它们同样算耦合，
        // 只是这些引用现在合法（app → engine:arknights），计数用于追踪剩余搬迁量
        "com.aliothmoon.maadroid.engine.arknights.",
    )

    /** 混杂包里按类名精确点出的方舟类型（尚未抽出的） */
    private val arknightsClasses = listOf(
        "com.aliothmoon.maadroid.data.config.MaaPathConfig",
    )

    /** 判定一行 import 是否指向方舟 */
    private fun isArknightsImport(trimmedLine: String): Boolean =
        arknightsPackages.any { trimmedLine.contains(it) } ||
            arknightsClasses.any { trimmedLine.contains(it) }

    /**
     * 方舟自身所在目录：它们引用方舟是正常的，不计入方向一。
     *
     * `domain/state` 与 `data/config` **不在此列** —— 它们各只有一个方舟类，
     * 整目录算作方舟会把同居的宿主类也豁免掉。
     */
    private val arknightsOwnDirs = listOf(
        "maa",
        "data/resource",
        "data/achievement",
        "data/repository",
        "presentation/view/panel",
    )

    /**
     * 各宿主目录当前对方舟的 import 行数；0 表示已干净、不得回退。
     *
     * 注意有几项在抽出 `engine:arknights` 时**上升**过，那不是回退而是**测量变准**：
     * `MaaApi` 原先住在通用的 `constant/` 包里，本表数不到它；拆成
     * `ArknightsApi`（方舟 API 与 MaaResource 源）与 `AppApi`（App 自更新、公告）
     * 之后，`data/api` 与 `data/datasource` 对方舟地址的真实依赖才显形。
     *
     * 这批依赖的正解是把资源地址挪到 `ResourcePackSpec` 上 —— 宿主的更新服务已按引擎
     * 遍历资源包，却仍从方舟的常量里取 URL，是遗留耦合。
     */
    private val hostToArknights = mapOf(
        // ---- 待清理：抽 engine/arknights 时逐条归零 ----
        "presentation/viewmodel" to 61,      // 方舟 ViewModel 尚未随面板迁出
        "domain/service" to 45,              // MaaCompositionService / MaaSessionLogger 等
        "data/model" to 39,                  // 方舟任务配置与宿主模型混居
        "koin" to 20,                        // 方舟类进宿主容器；改构造函数注入后可清零
        "presentation/view/background" to 10, // BackgroundTaskView 直连方舟 panel 符号
        "domain/usecase" to 9,               // AnalyzeTaskChainUseCase 独占多数
        "presentation/view/settings" to 8,   // 成就 UI
        "(顶层文件)" to 6,                    // MainActivity / MaaApplication
        "data/preferences" to 7,             // TaskChainState 整体是方舟任务链状态机
        "remote" to 5,                       // MaaCoreServiceImpl / MaaCoreManager
        "overlay" to 3,
        "presentation/state" to 2,
        "presentation/navigation" to 0,
        "presentation/components" to 1,      // RecruitTimeSelector / CoreCharSelector 误放
        "schedule" to 1,
        "utils" to 1,
        "manager" to 1,                      // RemoteServiceManager 取 MaaPathConfig
        "data/datasource" to 3,
        "data/api" to 3,
        "data/log" to 1,
        "engine" to 1,                       // EngineSetup 装配方舟，设计如此
        "domain/launch" to 1,
        "domain/models" to 1,
        "presentation/view/home" to 0,       // HomeView 显示 MaaExecutionState

        // ---- 已干净，严格锁 0 ----
        "theme" to 0,
        "service" to 0,
        "constant" to 0,
        "announcement" to 0,
        "data/notification" to 0,
        "domain/notification" to 0,
        "presentation/onboarding" to 0,
        "presentation/pip" to 0,
        "data/permission" to 0,
        "presentation/view/notification" to 0,
    )

    @Test
    fun hostReferencesToArknightsOnlyShrink() {
        val srcRoot = TestSources.inApp(APP_SOURCE_ROOT)
        val actual = countHostToArknights(srcRoot)
        assertRatchet("宿主 → 方舟", hostToArknights, actual)
    }

    // ------------------------------------------------------------------
    // 方向二：方舟 → 宿主（决定必须先建哪些 core:* 模块）
    // ------------------------------------------------------------------

    /**
     * 方舟代码依赖的宿主包。
     *
     * 按**宿主包**而非按方舟目录计数，因为这条轴直接对应「该往哪个 core 模块下沉」：
     * `presentation.components` + `theme` → `core:ui`；`utils.i18n` → `core:common`。
     * 数字降到 0 之日，即该依赖类别不再阻塞抽取。
     */
    private val arknightsToHost = mapOf(
        // → core:ui。主体已下沉（组件 76→4、主题 22→2）。
        // 残留的都是**方舟专属**、该去 engine/arknights 而非 core:ui 的东西：
        // RecruitTimeSelector(2) / CoreCharSelector(1) 是误放在宿主的方舟组件，
        // ResourceLoadingOverlay(1) 依赖 MaaResourceLoader，
        // LocalLogPalette + themedColor(2) 是日志色板，依赖宿主的日志模型（待 core:common）
        "com.aliothmoon.maadroid.presentation.components." to 4,
        "com.aliothmoon.maadroid.theme." to 2,
        // → core:common。UiText 主体已下沉（20→4）。残留 4 处是**方舟专属**的：
        // formatToolboxSyncTime(3) 只有方舟三个面板在用、wakeUpClientTypeDisplayName(1)
        // 是「开始唤醒」的服务器名 —— 都该随 engine/arknights 走，不是 core:common 的欠账
        "com.aliothmoon.maadroid.utils.i18n." to 4,
        // → 方舟侧改构造函数注入即可消除，非前置
        "com.aliothmoon.maadroid.presentation.viewmodel." to 15,
        // → 随 TaskChainState 一起迁入方舟（P2）。MaaPathConfig 已解耦（10→9），
        // 剩下的主要是 ActivityManager 对 TaskChainState 的依赖
        "com.aliothmoon.maadroid.data.preferences." to 7,
        "com.aliothmoon.maadroid.presentation.state." to 1,
        // 已为 0：一旦出现即是新增的反向依赖
        "com.aliothmoon.maadroid.koin." to 0,
    )

    @Test
    fun arknightsReferencesToHostOnlyShrink() {
        val srcRoot = TestSources.inApp(APP_SOURCE_ROOT)
        val actual = countArknightsToHost(srcRoot)
        assertRatchet("方舟 → 宿主（决定 core:* 拆分范围）", arknightsToHost, actual)
    }

    // ------------------------------------------------------------------

    private fun assertRatchet(
        direction: String,
        expected: Map<String, Int>,
        actual: Map<String, Int>,
    ) {
        if (actual == expected) return
        val diff = (actual.keys + expected.keys).sorted().mapNotNull { k ->
            val a = actual[k] ?: 0
            val e = expected[k] ?: 0
            when {
                a == e -> null
                a > e -> "  ↑ $k: $e → $a  （耦合增加了，请改用 engine-api 契约）"
                else -> "  ↓ $k: $e → $a  （已清理，请把基线降到 $a）"
            }
        }
        val table = actual.entries.sortedByDescending { it.value }
            .joinToString("\n") { "        \"${it.key}\" to ${it.value}," }
        assertEquals(
            "$direction 的耦合数与基线不符：\n" + diff.joinToString("\n") +
                "\n\n实测全表（可直接替换基线）：\n$table\n",
            expected,
            actual,
        )
    }

    private fun countHostToArknights(srcRoot: File): Map<String, Int> {
        val counts = linkedMapOf<String, Int>()
        for (file in sourceFiles(srcRoot)) {
            val rel = file.relativeTo(srcRoot).path.replace(File.separatorChar, '/')
            val key = hostDirectoryKeyOf(rel) ?: continue
            val n = file.readLines().count { line ->
                val t = line.trim()
                t.startsWith("import ") && isArknightsImport(t)
            }
            if (n > 0) counts[key] = (counts[key] ?: 0) + n
        }
        // 记 0 的目录也要出现，否则「目录被删掉」会被误读成已清理
        for (k in hostToArknights.keys) counts.putIfAbsent(k, 0)
        return counts
    }

    private fun countArknightsToHost(srcRoot: File): Map<String, Int> {
        val counts = linkedMapOf<String, Int>()
        for (dir in arknightsOwnDirs) {
            val d = File(srcRoot, dir)
            if (!d.isDirectory) continue
            for (file in sourceFiles(d)) {
                file.readLines().forEach { line ->
                    val t = line.trim()
                    if (!t.startsWith("import ")) return@forEach
                    arknightsToHost.keys.firstOrNull { t.contains(it) }?.let { pkg ->
                        counts[pkg] = (counts[pkg] ?: 0) + 1
                    }
                }
            }
        }
        for (k in arknightsToHost.keys) counts.putIfAbsent(k, 0)
        return counts
    }

    /**
     * 把文件归到基线表的键。
     *
     * 方舟自身目录返回 null（方向一不计）。深层目录归到基线表里存在的最长前缀，
     * 这样新增子目录会落进其父项而不是凭空多出一个键。
     */
    private fun hostDirectoryKeyOf(relativePath: String): String? {
        val dir = relativePath.substringBeforeLast('/', "")
        if (dir.isEmpty()) return ROOT_FILES_KEY
        if (arknightsOwnDirs.any { dir == it || dir.startsWith("$it/") }) return null
        return hostToArknights.keys
            .filter { dir == it || dir.startsWith("$it/") }
            .maxByOrNull { it.length }
            ?: dir
    }

    private fun sourceFiles(root: File): Sequence<File> =
        root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }

    private companion object {
        const val APP_SOURCE_ROOT = "src/main/java/com/aliothmoon/maadroid"

        /** 顶层单文件（MainActivity / MaaApplication 等）合并成一项 */
        const val ROOT_FILES_KEY = "(顶层文件)"
    }
}
