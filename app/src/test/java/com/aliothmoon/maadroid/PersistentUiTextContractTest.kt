package com.aliothmoon.maadroid

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentUiTextContractTest {

    @Test
    fun persistentUiMessages_useResourceBackedUiTextInsteadOfRawStringState() {
        val failures = TARGETS.mapNotNull { target ->
            val file = resolveSourceFile(target.relativePath)
            val matches = target.forbiddenPatterns.filter { pattern ->
                pattern.containsMatchIn(file.readText())
            }
            if (matches.isEmpty()) null else {
                val details = matches.joinToString { it.pattern }
                "${target.relativePath}: $details"
            }
        }

        assertTrue(
            "Persistent UI message state must use resource-backed UiText models instead of raw String fields:\n${failures.joinToString("\n")}",
            failures.isEmpty()
        )
    }

    private fun resolveSourceFile(relativePath: String): File {
        val candidates = listOf(
            File(relativePath),
            File("app/$relativePath"),
            File("../app/$relativePath"),
            // native 与 third/ 已拆到 core-bridge
            File("core-bridge/$relativePath"),
            File("../core-bridge/$relativePath"),
        )
        val file = candidates.firstOrNull { it.isFile }
        checkNotNull(file) { "Source file not found for test: $relativePath" }
        return file
    }

    private data class TargetFile(
        val relativePath: String,
        val forbiddenPatterns: List<Regex>,
    )

    companion object {
        private val TARGETS = listOf(
            TargetFile(
                "src/main/java/com/aliothmoon/maadroid/presentation/state/HomeUiState.kt",
                forbiddenPatterns = listOf(
                    Regex("""serviceStatusText\s*:\s*String"""),
                    Regex("""runModeUnsupportedMessage\s*:\s*String"""),
                ),
            ),
            TargetFile(
                "src/main/java/com/aliothmoon/maadroid/presentation/view/panel/FloatingPanelState.kt",
                forbiddenPatterns = listOf(
                    Regex("""title\s*:\s*String"""),
                    Regex("""message\s*:\s*String"""),
                    Regex("""confirmText\s*:\s*String"""),
                    Regex("""dismissText\s*:\s*String\?"""),
                ),
            ),
            TargetFile(
                "src/main/java/com/aliothmoon/maadroid/presentation/viewmodel/ToolboxViewModel.kt",
                forbiddenPatterns = listOf(
                    Regex("""statusMessage\s*:\s*StateFlow<String>"""),
                    Regex("""MutableStateFlow\(\"\"\)"""),
                ),
            ),
            TargetFile(
                "src/main/java/com/aliothmoon/maadroid/presentation/viewmodel/CopilotViewModel.kt",
                forbiddenPatterns = listOf(
                    Regex("""statusMessage\s*:\s*String\s*="""),
                ),
            ),
            TargetFile(
                "src/main/java/com/aliothmoon/maadroid/presentation/viewmodel/MiniGameDelegate.kt",
                forbiddenPatterns = listOf(
                    Regex("""statusMessage\s*:\s*String\s*="""),
                ),
            ),
            TargetFile(
                "src/main/java/com/aliothmoon/maadroid/schedule/ui/ScheduleEditViewModel.kt",
                forbiddenPatterns = listOf(
                    Regex("""errorMessage\s*:\s*String\?"""),
                ),
            ),
            TargetFile(
                "src/main/java/com/aliothmoon/maadroid/presentation/viewmodel/SettingsViewModel.kt",
                forbiddenPatterns = listOf(
                    Regex("""MutableStateFlow<String\?>\(null\)"""),
                    Regex("""StateFlow<String\?>"""),
                ),
            ),
        )
    }
}
