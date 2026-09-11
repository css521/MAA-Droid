package com.maadroid.app.engine

import org.junit.Assert.*
import org.junit.Test

class EngineExecutionCoordinatorTest {
    @Test fun runningGameRejectsOtherGamesAndDuplicateStarts() {
        val gate = EngineExecutionCoordinator()
        val running = requireNotNull(gate.tryStart("limbus"))
        assertNull(gate.tryStart("arknights"))
        assertNull(gate.tryStart("limbus"))
        assertNull(gate.tryPrepareResources("arknights"))
        assertEquals("limbus", gate.activeEngineId.value)
        running.close()
        assertNotNull(gate.tryStart("arknights"))
    }

    @Test fun sameGameResourcePreparationCanNestButKeepsOtherGameOut() {
        val gate = EngineExecutionCoordinator()
        val prepare = requireNotNull(gate.tryPrepareResources("arknights"))
        assertNull(gate.tryStart("limbus"))
        val run = requireNotNull(gate.tryStart("arknights"))
        val nested = requireNotNull(gate.tryPrepareResources("arknights"))
        run.close()
        nested.close()
        assertNull(gate.tryStart("limbus"))
        prepare.close()
        assertNull(gate.activeEngineId.value)
        assertNotNull(gate.tryStart("limbus"))
    }

    @Test fun oldLeaseCannotFreeNewRunOfSameGame() {
        val gate = EngineExecutionCoordinator()
        val old = requireNotNull(gate.tryStart("limbus"))
        old.close()
        val next = requireNotNull(gate.tryStart("limbus"))
        old.close()
        assertNull(gate.tryStart("arknights"))
        assertEquals("limbus", gate.activeEngineId.value)
        next.close()
        assertNull(gate.activeEngineId.value)
    }
}
