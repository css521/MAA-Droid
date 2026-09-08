package com.aliothmoon.maadroid

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class HomeViewRunModeCardContractTest {

    @Test
    fun runModeCard_doesNotRenderSecondaryDescriptionText() {
        val source = resolveSourceFile(
            "src/main/java/com/aliothmoon/maadroid/presentation/view/home/HomeView.kt"
        ).readText()

        assertFalse(source.contains("R.string.home_run_mode_fg_desc"))
        assertFalse(source.contains("R.string.home_run_mode_bg_desc"))
    }

    private fun resolveSourceFile(relativePath: String): File {
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
        checkNotNull(file) { "Source file not found for test: $relativePath" }
        return file
    }
}
