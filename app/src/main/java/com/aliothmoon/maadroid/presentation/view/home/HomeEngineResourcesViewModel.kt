package com.aliothmoon.maadroid.presentation.view.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliothmoon.maadroid.engine.EngineExecutionCoordinator
import com.aliothmoon.maadroid.engine.ResourcePackSpec
import com.aliothmoon.maadroid.engine.resource.EngineResourceService
import com.aliothmoon.maadroid.engine.resource.ResourcePhase
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
                state.installedVersion == null || state.phase == ResourcePhase.FAILED -> service.ensureInstalled(pack)
                state.availableRevision != null -> service.update(pack)
                else -> service.checkForUpdate(pack)
            }
        }
    }
}
