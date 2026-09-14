package com.maadroid.app.presentation.view.home

import com.maadroid.app.engine.EngineExecutionCoordinator
import com.maadroid.app.engine.ResourcePackSpec
import com.maadroid.app.engine.ResourceRevision
import com.maadroid.app.engine.resource.EngineResourceService
import com.maadroid.app.engine.resource.EngineResourceState
import com.maadroid.app.engine.resource.ResourcePhase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test
import java.io.File

class HomeEngineResourcesViewModelTest {
    private class Fixture(scope: CoroutineScope) {
        val pack = mockk<ResourcePackSpec> { every { packId } returns "limbus" }
        val state = MutableStateFlow(EngineResourceState())
        val service = mockk<EngineResourceService>()
        val coordinator = EngineExecutionCoordinator()
        val viewModel = HomeEngineResourcesViewModel(service, coordinator, scope)

        init {
            every { service.state(pack) } returns state
            coEvery { service.ensureInstalled(pack) } returns Result.success(File("resources"))
            coEvery { service.reinstall(pack) } returns Result.success(File("resources"))
            coEvery { service.update(pack) } returns Result.success(File("resources"))
            coEvery { service.checkForUpdate(pack) } returns Result.success(null)
        }
    }

    /**
     * 已装但失败时走重装，而不是 ensureInstalled —— 后者在"已装且文件完好"时只 ready()
     * 不联网，于是「远端同名 tag 指向了新提交」被守卫拒绝后无路可走（状态翻回 READY，
     * 再检查更新又同样失败）。
     */
    @Test fun failedInstalledResourcesReinstallFromTheRemoteLatest() = runBlocking {
        val f = Fixture(this)
        f.state.value = EngineResourceState(phase = ResourcePhase.FAILED, installedVersion = "5.0.0")
        f.viewModel.perform(f.pack)
        yield()
        coVerify(exactly = 1) { f.service.reinstall(f.pack) }
        coVerify(exactly = 0) { f.service.ensureInstalled(any()) }
        coVerify(exactly = 0) { f.service.update(any()) }
        coVerify(exactly = 0) { f.service.checkForUpdate(any()) }
    }

    /**
     * 尚未安装时始终走 ensureInstalled —— 哪怕上一次就是失败的。它装编译期钉住的
     * initialRevision，走 Release asset 直链不碰 tags API，是匿名访问被限流时唯一能装上的路。
     */
    @Test fun firstInstallUsesThePinnedRevisionEvenAfterAFailure() = runBlocking {
        val f = Fixture(this)
        f.viewModel.perform(f.pack)
        yield()
        f.state.value = EngineResourceState(phase = ResourcePhase.FAILED, installedVersion = null)
        f.viewModel.perform(f.pack)
        yield()
        coVerify(exactly = 2) { f.service.ensureInstalled(f.pack) }
        coVerify(exactly = 0) { f.service.reinstall(any()) }
    }

    @Test fun installedResourcesCheckThenApplyTheAvailableRevision() = runBlocking {
        val f = Fixture(this)
        f.state.value = EngineResourceState(phase = ResourcePhase.READY, installedVersion = "5.0.0")
        f.viewModel.perform(f.pack)
        yield()
        f.state.value = f.state.value.copy(availableRevision = ResourceRevision("v5.0.1", "a".repeat(40)))
        f.viewModel.perform(f.pack)
        yield()
        coVerify(exactly = 1) { f.service.checkForUpdate(f.pack) }
        coVerify(exactly = 1) { f.service.update(f.pack) }
        coVerify(exactly = 0) { f.service.ensureInstalled(any()) }
    }

    @Test fun activeTasksAndBusyResourceOperationsBlockActions() = runBlocking {
        val f = Fixture(this)
        val reservation = checkNotNull(f.coordinator.tryStart("arknights"))
        try {
            f.viewModel.perform(f.pack)
            yield()
        } finally {
            reservation.close()
        }
        f.state.value = EngineResourceState(phase = ResourcePhase.DOWNLOADING)
        f.viewModel.perform(f.pack)
        yield()
        coVerify(exactly = 0) { f.service.ensureInstalled(any()) }
        coVerify(exactly = 0) { f.service.update(any()) }
        coVerify(exactly = 0) { f.service.checkForUpdate(any()) }
    }

    @Test fun oneDownloadContinuesAfterTheUiStopsObservingAndRepeatedClicksDoNotQueueAnother() = runBlocking {
        val f = Fixture(this)
        val download = CompletableDeferred<Unit>()
        coEvery { f.service.ensureInstalled(f.pack) } coAnswers {
            download.await()
            f.state.value = EngineResourceState(phase = ResourcePhase.READY, installedVersion = "5.0.0")
            Result.success(File("resources"))
        }
        val observer = launch { f.state.collect { } }
        f.viewModel.perform(f.pack)
        yield()
        f.viewModel.perform(f.pack)
        observer.cancelAndJoin()
        download.complete(Unit)
        yield()
        f.viewModel.perform(f.pack)
        yield()
        coVerify(exactly = 1) { f.service.ensureInstalled(f.pack) }
        coVerify(exactly = 1) { f.service.checkForUpdate(f.pack) }
    }
}
