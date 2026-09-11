package com.maadroid.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class HomeViewRunModeCardContractTest {

    @Test
    fun runModeCard_doesNotRenderSecondaryDescriptionText() {
        val source = resolveSourceFile(
            "src/main/java/com/maadroid/app/presentation/view/home/HomeView.kt"
        ).readText()

        assertFalse(source.contains("R.string.home_run_mode_fg_desc"))
        assertFalse(source.contains("R.string.home_run_mode_bg_desc"))
    }

    /** 统一走 [TestSources]：模块目录名不写死，挪模块时不必回来改 */
    private fun resolveSourceFile(relativePath: String): File =
        TestSources.resolve(relativePath)
}
