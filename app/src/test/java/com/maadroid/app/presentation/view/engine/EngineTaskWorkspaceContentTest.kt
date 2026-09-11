package com.maadroid.app.presentation.view.engine

import android.os.Trace
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import com.maadroid.app.engine.AutomationEngine
import com.maadroid.app.engine.Capability
import com.maadroid.app.engine.DisplaySpec
import com.maadroid.app.engine.EngineProvider
import com.maadroid.app.engine.EngineRegistry
import com.maadroid.app.engine.EngineResources
import com.maadroid.app.engine.EngineUi
import com.maadroid.app.engine.EngineWorkspace
import com.maadroid.app.engine.GameProfile
import com.maadroid.app.engine.ResourcePackSpec
import com.maadroid.app.engine.TaskPanelSpec
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Compose the host's actual workspace dispatch, without a device, Koin or an installed resource pack. */
class EngineTaskWorkspaceContentTest {
    @get:Rule val temp = TemporaryFolder()

    @Before fun setUp() {
        // The Android Compose runtime only needs tracing stubs for this node-free composition.
        mockkStatic(Trace::class)
        every { Trace.beginSection(any()) } just Runs
        every { Trace.endSection() } just Runs
        mockkObject(EngineRegistry)
    }

    @After fun tearDown() {
        unmockkObject(EngineRegistry)
        unmockkStatic(Trace::class)
    }

    @Test fun workspaceReceivesBothPacksByIdBeforeDownloadAndKeepsEditingCallbacks() {
        val models = Pack("test-models", "resources/models")
        val main = Pack("test-main", "resources/main")
        val workspace = RecordingWorkspace()
        val provider = Provider(listOf(models, main), workspace)
        every { EngineRegistry.provider(ENGINE_ID) } returns provider
        val root = File(temp.root, "not-downloaded")
        val directories = mapOf(models.packId to File(root, models.relativeRoot), main.packId to File(root, main.relativeRoot))
        val resolved = mutableListOf<ResourcePackSpec>()
        val logs = listOf("idle")
        val draft = "{\"saved\":true}"
        var saved: String? = null
        assertFalse(root.exists())

        compose {
            EngineTaskWorkspaceContent(
                engineId = ENGINE_ID,
                configJson = draft,
                onConfigChange = { saved = it },
                editable = true,
                logs = logs,
                directoryForPack = { pack ->
                    resolved += pack
                    directories.getValue(pack.packId)
                },
            )
        }

        val received = requireNotNull(workspace.received)
        assertEquals(listOf(models, main), resolved)
        assertEquals(ENGINE_ID, received.resources.engineId)
        assertEquals(directories, received.resources.directories)
        assertEquals(directories.getValue(main.packId), received.resources.requireDirectory(main))
        assertEquals(directories.getValue(models.packId), received.resources.requireDirectory(models))
        assertEquals(draft, received.configJson)
        assertEquals(logs, received.logs)
        assertTrue(received.editable)
        assertNull(saved)
        received.onConfigChange("{\"saved\":false}")
        assertEquals("{\"saved\":false}", saved)
        assertFalse("Rendering the workspace must not create resource directories", root.exists())
    }

    @Test fun workspaceWithNoPacksReceivesAnEmptyResourceSetAndTheRunningLock() {
        val workspace = RecordingWorkspace()
        every { EngineRegistry.provider(ENGINE_ID) } returns Provider(emptyList(), workspace)

        compose {
            EngineTaskWorkspaceContent(
                engineId = ENGINE_ID,
                configJson = "saved draft",
                onConfigChange = { error("Rendering must not replace the saved draft") },
                editable = false,
                logs = listOf("running"),
                directoryForPack = { error("There are no resource packs to resolve") },
            )
        }

        val received = requireNotNull(workspace.received)
        assertEquals(ENGINE_ID, received.resources.engineId)
        assertTrue(received.resources.directories.isEmpty())
        assertEquals("saved draft", received.configJson)
        assertFalse(received.editable)
    }

    @Test fun unregisteredGameDoesNotCreateResourcesOrInvokeWorkspaceCallbacks() {
        every { EngineRegistry.provider("removed-game") } returns null

        compose {
            EngineTaskWorkspaceContent(
                engineId = "removed-game",
                configJson = "saved draft",
                onConfigChange = { error("An unregistered game must not replace the saved draft") },
                editable = true,
                logs = emptyList(),
                directoryForPack = { error("An unregistered game has no resource profile") },
            )
        }
    }

    @Test fun taskPanelOnlyProviderDoesNotResolveWorkspaceResources() {
        every { EngineRegistry.provider(ENGINE_ID) } returns Provider(listOf(Pack("main", "resources/main")), null)

        compose {
            EngineTaskWorkspaceContent(
                engineId = ENGINE_ID,
                configJson = "",
                onConfigChange = { error("No workspace was registered") },
                editable = true,
                logs = emptyList(),
                directoryForPack = { error("The task panel fallback does not render a workspace") },
            )
        }
    }

    private fun compose(content: @Composable () -> Unit) {
        val recomposer = Recomposer(EmptyCoroutineContext)
        val composition = Composition(object : AbstractApplier<Unit>(Unit) {
            override fun insertTopDown(index: Int, instance: Unit) = error("Workspace dispatch must not add UI nodes")
            override fun insertBottomUp(index: Int, instance: Unit) = error("Workspace dispatch must not add UI nodes")
            override fun remove(index: Int, count: Int) = error("No UI nodes were inserted")
            override fun move(from: Int, to: Int, count: Int) = error("No UI nodes were inserted")
            override fun onClear() = Unit
        }, recomposer)
        try {
            composition.setContent(content)
        } finally {
            composition.dispose()
            recomposer.cancel()
        }
    }

    private data class ContentCall(
        val configJson: String,
        val onConfigChange: (String) -> Unit,
        val editable: Boolean,
        val logs: List<String>,
        val resources: EngineResources,
    )

    private class RecordingWorkspace : EngineWorkspace {
        var received: ContentCall? = null
        override fun initialConfig(enabled: Map<String, Boolean>, taskParams: Map<String, String>) = "{}"
        override fun selectedTasks(configJson: String) = emptyList<Pair<String, String>>()

        @Composable
        override fun Content(
            configJson: String,
            onConfigChange: (String) -> Unit,
            editable: Boolean,
            logs: List<String>,
            resources: EngineResources,
        ) {
            SideEffect { received = ContentCall(configJson, onConfigChange, editable, logs, resources) }
        }
    }

    private class Provider(packs: List<ResourcePackSpec>, workspace: EngineWorkspace?) : EngineProvider {
        override val profile = object : GameProfile {
            override val id = ENGINE_ID
            override val displayNameRes = 0
            override val iconRes = 0
            override val gamePackages = emptyList<String>()
            override val display = DisplaySpec(1280, 720)
            override val resourcePacks = packs
            override val capabilities = emptySet<Capability>()
        }
        override val ui = object : EngineUi {
            override val workspace = workspace
            override val taskPanels = if (workspace == null) {
                listOf(TaskPanelSpec("test-task", titleRes = 0) { _, _ -> })
            } else emptyList()
        }
        override fun createEngine(): AutomationEngine = error("Rendering must not construct a running engine")
    }

    private class Pack(override val packId: String, override val relativeRoot: String) : ResourcePackSpec {
        override val engineId = ENGINE_ID
        override val bundledAssetPrefix: String? = null
        override val requiresPrivilegedDelivery = false
        override fun readInstalledVersion(resourceDir: File): String? = error("UI dispatch must not read resource files")
        override fun mapZipEntry(entryName: String): String? = error("UI dispatch must not extract resources")
        override fun invalidateInstalledVersion(resourceDir: File) = error("UI dispatch must not change resource files")
        override fun checkCompatibility(manifestJson: String?): String? = error("UI dispatch must not validate installation")
    }

    companion object {
        private const val ENGINE_ID = "workspace-test"
    }
}
