package com.maadroid.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeBuildLocalOptContractTest {

    @Test
    fun localAbiAndLtoSwitchesAreWired() {
        // ABI / LTO 开关必须落在拥有 native 构建的模块（core-bridge）里：
        // app 只声明 abiFilters 无法约束别的模块的 externalNativeBuild
        val gradle = nativeModuleFile("build.gradle.kts").readText()
        assertTrue(gradle.contains("maa.abi"))
        assertTrue(gradle.contains("maa.nativeLto"))
        assertTrue(gradle.contains("MAA_NATIVE_LTO"))
        assertTrue(gradle.contains("arm64-v8a"))
        assertTrue(gradle.contains("x86_64"))

        val cmake = nativeModuleFile(NATIVE_MARKER).readText()
        assertTrue(cmake.contains("option(MAA_NATIVE_LTO"))
        assertTrue(cmake.contains("add_compile_options(-flto)"))
        assertTrue(cmake.contains("add_link_options(-flto)"))
    }

    /**
     * 在**拥有 native 构建**的模块下定位。
     *
     * 不写死 core/bridge：用例关心的是「谁拥有 CMakeLists.txt」，
     * 这样 native 代码将来再挪模块也不必改这里。
     * 也不能用 TestSources.resolve —— build.gradle.kts 每个模块都有，
     * 会读到 app 的那份（实测踩过）。
     */
    private fun nativeModuleFile(relativePath: String): File =
        TestSources.inModuleOwning(NATIVE_MARKER, relativePath)

    private companion object {
        const val NATIVE_MARKER = "src/main/native/CMakeLists.txt"
    }
}
