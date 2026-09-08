package com.aliothmoon.maadroid

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeBuildLocalOptContractTest {

    @Test
    fun localAbiAndLtoSwitchesAreWired() {
        // ABI / LTO 开关必须落在拥有 native 构建的模块（core-bridge）里：
        // app 只声明 abiFilters 无法约束别的模块的 externalNativeBuild
        val gradle = resolveNativeModule("build.gradle.kts").readText()
        assertTrue(gradle.contains("maa.abi"))
        assertTrue(gradle.contains("maa.nativeLto"))
        assertTrue(gradle.contains("MAA_NATIVE_LTO"))
        assertTrue(gradle.contains("arm64-v8a"))
        assertTrue(gradle.contains("x86_64"))

        val cmake = resolve("src/main/native/CMakeLists.txt").readText()
        assertTrue(cmake.contains("option(MAA_NATIVE_LTO"))
        assertTrue(cmake.contains("add_compile_options(-flto)"))
        assertTrue(cmake.contains("add_link_options(-flto)"))
    }

    /** native 构建归 core-bridge，故优先在该模块下定位 */
    private fun resolveNativeModule(relativePath: String): File {
        val candidates = listOf(
            File("core-bridge/$relativePath"),
            File("../core-bridge/$relativePath"),
            File("core-remote/$relativePath"),
            File("../core-remote/$relativePath"),
        )
        val file = candidates.firstOrNull { it.isFile }
        checkNotNull(file) { "File not found for test: core-bridge/$relativePath" }
        return file
    }

    private fun resolve(relativePath: String): File {
        val candidates = listOf(
            File(relativePath),
            File("app/$relativePath"),
            File("../app/$relativePath"),
            // native 与 third/ 已拆到 core-bridge
            File("core-bridge/$relativePath"),
            File("../core-bridge/$relativePath"),
            File("core-remote/$relativePath"),
            File("../core-remote/$relativePath"),
        )
        val file = candidates.firstOrNull { it.isFile }
        checkNotNull(file) { "File not found for test: $relativePath" }
        return file
    }
}
