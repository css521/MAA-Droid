package com.maadroid.app.presentation.view.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maadroid.app.engine.EngineExecutionCoordinator
import com.maadroid.app.engine.ResourcePackSpec
import com.maadroid.app.engine.resource.EngineResourceService
import com.maadroid.app.engine.resource.ResourcePhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** A lazy Home item can leave composition while downloading; the navigation owner keeps this job. */
internal class HomeEngineResourcesViewModel(
    private val service: EngineResourceService,
    private val coordinator: EngineExecutionCoordinator = EngineExecutionCoordinator.shared,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ViewModel(scope) {
    private val operations = mutableMapOf<String, Job>()

    fun perform(pack: ResourcePackSpec) {
        if (coordinator.activeEngineId.value != null || operations[pack.packId]?.isActive == true) return
        operations[pack.packId] = viewModelScope.launch {
            if (coordinator.activeEngineId.value != null) return@launch
            val state = service.state(pack).value
            if (state.busy) return@launch
            when {
                // 首装单独一条，且必须排在 FAILED 之前：ensureInstalled 装的是编译期钉住的
                // initialRevision，走 Release asset 直链、不碰 tags API —— 匿名访问被限流
                // （403/429）时这是唯一还能装上的路。
                state.installedVersion == null -> service.ensureInstalled(pack)
                // 已装但失败：走重装。ensureInstalled 在"已装且文件完好"时只 ready() 不联网，
                // 拿它当「修复」会让 SHA 守卫拒绝覆盖的情形陷入死循环（见 reinstall 的注释）。
                state.phase == ResourcePhase.FAILED -> service.reinstall(pack)
                state.availableRevision != null -> service.update(pack)
                else -> service.checkForUpdate(pack)
            }
        }
    }
}
