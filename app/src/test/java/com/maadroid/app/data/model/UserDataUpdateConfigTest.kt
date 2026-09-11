package com.maadroid.app.data.model

import com.maadroid.app.data.repository.DepotRepository
import com.maadroid.app.data.repository.DepotSnapshot
import com.maadroid.app.data.repository.OperBoxRepository
import com.maadroid.app.data.repository.OperBoxSnapshot
import com.maadroid.app.domain.models.UserDataUpdateTriggerInterval
import com.maadroid.app.maa.task.MaaTaskType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserDataUpdateConfigTest {

    private fun ctx(
        operSync: Long = 0L,
        depotSync: Long = 0L,
    ): TaskParamContext {
        val operRepo = mockk<OperBoxRepository> {
            every { snapshot } returns MutableStateFlow(OperBoxSnapshot(syncTimeMillis = operSync))
        }
        val depotRepo = mockk<DepotRepository> {
            every { snapshot } returns MutableStateFlow(DepotSnapshot(syncTimeMillis = depotSync))
        }
        return testTaskParamContext(
            operBoxRepository = operRepo,
            depotRepository = depotRepo,
        )
    }

    @Test
    fun bothSwitchesOff_producesNothing() {
        val result = UserDataUpdateConfig(updateOperBox = false, updateDepot = false)
            .toTaskParams(ctx())
        assertTrue(result.isEmpty())
    }

    @Test
    fun everyTime_bothOn_ordersOperBoxThenDepot() {
        val result = UserDataUpdateConfig().toTaskParams(ctx())
        assertEquals(
            listOf(MaaTaskType.OPER_BOX, MaaTaskType.DEPOT),
            result.map { it.type },
        )
    }

    @Test
    fun onlyDepot_producesDepotOnly() {
        val result = UserDataUpdateConfig(updateOperBox = false, updateDepot = true)
            .toTaskParams(ctx())
        assertEquals(listOf(MaaTaskType.DEPOT), result.map { it.type })
    }

    @Test
    fun daily_recentSync_skips() {
        val now = System.currentTimeMillis()
        val result = UserDataUpdateConfig(
            triggerInterval = UserDataUpdateTriggerInterval.DAILY,
        ).toTaskParams(ctx(operSync = now, depotSync = now))
        assertTrue(result.isEmpty())
    }
}
