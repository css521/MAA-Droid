package com.aliothmoon.maadroid

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模块边界契约：core-* 不得依赖 engine-* 或应用层。
 *
 * 多引擎宿主的全部可扩展性都建立在「依赖方向单向」之上 ——
 * app / engine-* → engine-api → core-* → hidden-api。
 * 一旦 core-* 反向引用了某个引擎，第二个游戏就再也接不进来，
 * 而这种倒挂只需要一次「顺手 import」就会发生，所以用测试钉住。
 */
class ModuleBoundaryContractTest {

    /** core 模块禁止出现的包前缀：具体引擎、以及宿主应用层 */
    private val forbidden = listOf(
        "com.aliothmoon.maadroid.maa.",
        "com.aliothmoon.maadroid.engine.arknights",
        "com.aliothmoon.maadroid.engine.limbus",
        "com.aliothmoon.maadroid.presentation.",
        "com.aliothmoon.maadroid.koin.",
        "com.aliothmoon.maadroid.schedule.",
        "com.aliothmoon.maadroid.announcement.",
        "com.aliothmoon.maadroid.data.repository",
        "com.aliothmoon.maadroid.data.resource",
        "com.aliothmoon.maadroid.data.achievement",
    )

    @Test
    fun coreModulesDoNotDependOnEnginesOrApp() {
        val offenders = mutableListOf<String>()
        for (module in listOf("core-bridge", "core-remote")) {
            val src = resolveModuleSrc(module) ?: continue
            src.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .forEach { file ->
                    file.readLines().forEachIndexed { i, line ->
                        val trimmed = line.trim()
                        if (!trimmed.startsWith("import ")) return@forEachIndexed
                        forbidden.firstOrNull { trimmed.contains(it) }?.let { bad ->
                            offenders += "$module/${file.name}:${i + 1} → $bad"
                        }
                    }
                }
        }
        assertTrue(
            "core-* 出现了对引擎/应用层的依赖，会让多引擎架构失效：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /** 测试的工作目录随模块变化，两种起点都要能定位 */
    private fun resolveModuleSrc(module: String): File? =
        listOf(File("$module/src/main"), File("../$module/src/main"))
            .firstOrNull { it.isDirectory }
}
