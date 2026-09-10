package com.aliothmoon.maadroid.engine.limbus

import com.aliothmoon.maadroid.engine.AutomationEngine
import com.aliothmoon.maadroid.engine.EngineEvent
import com.aliothmoon.maadroid.engine.EngineResources
import com.aliothmoon.maadroid.engine.GameProfile
import com.aliothmoon.maadroid.engine.LogLevel
import com.aliothmoon.maadroid.engine.ResourcePackSpec
import com.aliothmoon.maadroid.engine.limbus.fixtures.LalcV500Fixtures
import com.aliothmoon.maadroid.engine.limbus.pipeline.PipelineRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class LimbusEngineResourcesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun duplicateTaskIsRejectedAtAppendWithoutConsumingAnId() = runTest {
        val files = LalcV500Fixtures.taskFiles()
        files.forEach { (name, json) ->
            File(temp.root, "config/task/$name").apply { parentFile!!.mkdirs(); writeText(json) }
        }
        // prepare indexes names without decoding images; connect owns native loading.
        PipelineRegistry.load(files).referencedTemplates().forEach { name ->
            File(temp.root, "img/general/$name.png").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf()) }
        }
        val engine = LimbusEngine(backgroundScope)
        try {
            engine.prepare(EngineResources(LimbusProfile, mapOf(LimbusResourcePack.packId to temp.root))).getOrThrow()
            val first = engine.appendTask("exp", "{}")
            assertTrue(first > 0)
            assertEquals(AutomationEngine.INVALID_TASK_ID, engine.appendTask("exp", "{}"))
            assertEquals(first + 1, engine.appendTask("mail", "{}"))
            assertTrue(engine.setTaskParams(first, "{\"exp\":{\"check_node_target_count\":3}}"))
        } finally {
            engine.release()
        }
    }

    @Test fun missingLimbusPackFailsBeforeLoadingAndEmitsTheExistingFailureEvents() = runTest {
        assertRejectedBeforeLoading(
            EngineResources(LimbusProfile, emptyMap()),
            IllegalStateException::class.java,
            "Missing resource directory for pack ${LimbusResourcePack.packId} (engine ${LimbusProfile.id})",
        )
    }

    @Test fun foreignCollectionWithTheSamePackIdCannotLoadLimbusResources() = runTest {
        val foreignPack = object : ResourcePackSpec by LimbusResourcePack {
            override val engineId = "other-game"
        }
        val foreignProfile = object : GameProfile by LimbusProfile {
            override val id = foreignPack.engineId
            override val resourcePacks = listOf(foreignPack)
        }
        assertRejectedBeforeLoading(
            EngineResources(foreignProfile, mapOf(foreignPack.packId to File("not-a-limbus-resource"))),
            IllegalArgumentException::class.java,
            "Resource pack ${LimbusResourcePack.packId} belongs to ${LimbusProfile.id}, not ${foreignProfile.id}",
        )
    }

    private suspend fun TestScope.assertRejectedBeforeLoading(
        resources: EngineResources,
        failureType: Class<out Throwable>,
        expectedMessage: String,
    ) {
        val engine: AutomationEngine = LimbusEngine(backgroundScope)
        val received = mutableListOf<EngineEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.events.collect { received += it }
        }
        try {
            val result = engine.prepare(resources)
            runCurrent()

            assertTrue(result.isFailure)
            val cause = result.exceptionOrNull()!!
            assertEquals(failureType, cause.javaClass)
            // A loading attempt would fail on config/task instead of resource ownership/presence.
            assertEquals(expectedMessage, cause.message)
            val reason = "装载资源失败: $expectedMessage"
            assertEquals(2, received.size)
            assertEquals(EngineEvent.Log(LogLevel.Error, reason), received[0])
            val failure = received[1] as EngineEvent.Failure
            assertEquals(reason, failure.reason)
            assertEquals(failureType, failure.cause?.javaClass)
            assertEquals(expectedMessage, failure.cause?.message)
            assertFalse(engine.isRunning)
            assertEquals(AutomationEngine.INVALID_TASK_ID, engine.appendTask("mirror", "{}"))
        } finally {
            engine.release()
        }
    }
}
