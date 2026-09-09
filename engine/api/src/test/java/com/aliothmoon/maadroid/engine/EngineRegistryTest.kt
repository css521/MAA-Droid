package com.aliothmoon.maadroid.engine

import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class EngineRegistryTest {
    @Before fun setUp() = EngineRegistry.clearForTest()
    @After fun tearDown() = EngineRegistry.clearForTest()

    @Test fun metadataLookupsDoNotCreateRunningEngines() {
        val provider = Provider("test")
        EngineRegistry.register(provider)
        assertEquals(listOf(provider.profile), EngineRegistry.profiles())
        assertSame(provider, EngineRegistry.provider("test"))
        assertSame(provider.profile, EngineRegistry.resolveOrFallback("missing"))
        assertTrue(EngineRegistry.isRegistered("test"))
        assertTrue(EngineRegistry.allResourcePacks().isEmpty())
        assertEquals(0, provider.created.size)
    }

    @Test fun factoryCallsCreateIndependentInstancesAndEventStreams() {
        val provider = Provider("test")
        EngineRegistry.register(provider)
        val first = EngineRegistry.createEngine("test")!!
        first.release()
        val next = EngineRegistry.createEngine("test")!!
        assertNotSame(first, next)
        assertNotSame(first.events, next.events)
        assertEquals(2, provider.created.size)
        assertEquals(1, provider.created.first().releaseCount)
        assertEquals(0, provider.created.last().releaseCount)
        next.release()
    }

    @Test fun replacingAProviderUsesItsFactoryWithoutReleasingTheCallersInstance() {
        val old = Provider("test")
        val replacement = Provider("test")
        EngineRegistry.register(old)
        val first = EngineRegistry.createEngine("test")!!
        EngineRegistry.register(replacement)
        val next = EngineRegistry.createEngine("test")!!
        assertSame(replacement.created.single(), next)
        assertNotSame(first, next)
        assertEquals(0, old.created.single().releaseCount)
        first.release()
        next.release()
    }

    @Test fun unknownEngineHasNoFactory() {
        assertNull(EngineRegistry.createEngine("missing"))
    }

    private class Provider(engineId: String) : EngineProvider {
        override val profile = object : GameProfile {
            override val id = engineId
            override val displayNameRes = 0
            override val iconRes = 0
            override val gamePackages = emptyList<String>()
            override val display = DisplaySpec(1280, 720)
            override val resourcePacks = emptyList<ResourcePackSpec>()
            override val capabilities = emptySet<Capability>()
        }
        override val ui = object : EngineUi {
            override val taskPanels = emptyList<TaskPanelSpec>()
        }
        val created = mutableListOf<Engine>()
        override fun createEngine(): AutomationEngine = Engine(profile).also { created += it }
    }

    private class Engine(override val profile: GameProfile) : AutomationEngine {
        override val events = MutableSharedFlow<EngineEvent>()
        override val isRunning = false
        var releaseCount = 0
        override suspend fun prepare(resourceDir: File) = Result.success(Unit)
        override suspend fun connect(device: DeviceHandle) = Result.success(Unit)
        override fun appendTask(type: String, paramsJson: String) = 1
        override fun setTaskParams(taskId: Int, paramsJson: String) = true
        override suspend fun start() = true
        override suspend fun stop() = true
        override fun release() { releaseCount++ }
    }
}
