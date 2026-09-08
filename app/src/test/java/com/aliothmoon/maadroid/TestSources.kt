package com.aliothmoon.maadroid

import java.io.File

/** 契约测试的源码定位：兼容从仓库根或 app 模块目录启动 */
internal object TestSources {

    fun resolve(relativePath: String): File =
        candidates(relativePath).firstOrNull { it.isFile }
            ?: error("source not found: $relativePath (cwd=${File(".").absolutePath})")

    fun resolveDir(relativePath: String): File =
        candidates(relativePath).firstOrNull { it.isDirectory }
            ?: error("dir not found: $relativePath (cwd=${File(".").absolutePath})")

    private fun candidates(relativePath: String) = listOf(
        File(relativePath),
        File("app/$relativePath"),
        File("../app/$relativePath"),
        // native 代码与 scrcpy 派生封装已拆到 core-bridge，契约测试需同时能定位两个模块
        File("core-bridge/$relativePath"),
        File("../core-bridge/$relativePath"),
        File("core-remote/$relativePath"),
        File("../core-remote/$relativePath"),
    )
}
