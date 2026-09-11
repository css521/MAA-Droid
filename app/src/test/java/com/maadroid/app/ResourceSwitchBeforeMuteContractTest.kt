package com.maadroid.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 换进程时旧进程收尾会 restoreAll() 解除静音，重连后 autoRestore 还会清掉 marker
 * 所以资源档对齐必须排在 mute 之前，否则跨服那次任务全程不静音且不自愈
 */
class ResourceSwitchBeforeMuteContractTest {

    @Test
    fun startTaskChainUseCase_prepareResourcesBeforeMute() {
        assertOrdering(
            "src/main/java/com/maadroid/app/domain/launch/StartTaskChainUseCase.kt",
            "composition.prepareResources(",
            "muteCoordinator.mute(",
        )
    }

    @Test
    fun backgroundTaskViewModel_prepareResourcesBeforeMute() {
        assertOrdering(
            "src/main/java/com/maadroid/app/presentation/viewmodel/BackgroundTaskViewModel.kt",
            "compositionService.prepareResources(",
            "gameMuteCoordinator.mute(",
        )
    }

    @Test
    fun prepareResources_refusesToRecycleServiceWhileBusy() {
        val source = resolve(
            "src/main/java/com/maadroid/app/domain/service/MaaCompositionService.kt"
        ).readText()
        val body = source.substringAfter("suspend fun prepareResources(")
            .substringBefore("private suspend fun checkPreconditions(")
        assertTrue(
            "prepareResources 只能在 IDLE/ERROR 下换资源，否则会杀掉运行中的虚拟显示器",
            body.contains("MaaExecutionState.IDLE") && body.contains("MaaExecutionState.ERROR"),
        )
        assertTrue(body.contains("resourceLoader.ensureLoaded(clientType)"))
    }

    private fun assertOrdering(relativePath: String, first: String, second: String) {
        val source = resolve(relativePath).readText()
        val firstIndex = source.indexOf(first)
        val secondIndex = source.indexOf(second)
        assertTrue("$relativePath 里找不到 $first", firstIndex >= 0)
        assertTrue("$relativePath 里找不到 $second", secondIndex >= 0)
        assertTrue(
            "$relativePath: $first 必须排在 $second 之前，换进程会解除游戏静音",
            firstIndex < secondIndex,
        )
    }

    /** 统一走 [TestSources]：模块目录名不写死，挪模块时不必回来改 */
    private fun resolve(relativePath: String): File =
        TestSources.resolve(relativePath)
}
