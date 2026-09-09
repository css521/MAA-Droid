package com.aliothmoon.maadroid.presentation.state

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 同一宿主的引擎任务页共用一个设备通道；切换 UI 不释放占用。 */
class EngineTaskExecutionState : ViewModel() {
    private val active = MutableStateFlow<String?>(null)
    val activeEngineId = active.asStateFlow()

    fun tryAcquire(engineId: String): Boolean = active.compareAndSet(null, engineId)

    fun release(engineId: String) {
        active.compareAndSet(engineId, null)
    }
}
