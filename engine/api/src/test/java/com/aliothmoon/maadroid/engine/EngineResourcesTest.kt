package com.aliothmoon.maadroid.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EngineResourcesTest {
    private val main = Pack("main", "test")
    private val models = Pack("models", "test")

    @Test fun directoriesAreAnImmutableSnapshotOfTheSuppliedMap() {
        val original = File("resources/main")
        val supplied = linkedMapOf(main.packId to original)
        val resources = EngineResources(profile(main, models), supplied)

        supplied[main.packId] = File("resources/replaced")
        supplied[models.packId] = File("resources/models")
        supplied.clear()

        assertEquals("test", resources.engineId)
        assertEquals(mapOf(main.packId to original), resources.directories)
        assertSame(original, resources.requireDirectory(main))
        assertNull(resources.directory(models))

        val mutableView = resources.directories as MutableMap<String, File>
        assertThrows(UnsupportedOperationException::class.java) {
            mutableView[main.packId] = File("resources/replaced")
        }
        assertThrows(UnsupportedOperationException::class.java) {
            mutableView.entries.single().setValue(File("resources/replaced"))
        }
        assertThrows(UnsupportedOperationException::class.java) {
            mutableView.keys.remove(main.packId)
        }
        assertEquals(mapOf(main.packId to original), resources.directories)
    }

    @Test fun directoryKeysMustBeDeclaredByTheProfile() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            EngineResources(profile(main), mapOf("other-game-main" to File("resources/other")))
        }
        assertTrue(failure.message.orEmpty().contains("other-game-main"))
        assertTrue(failure.message.orEmpty().contains("not declared by engine test"))
    }

    @Test fun aProfileCannotSupplyPacksBelongingToAnotherEngine() {
        val foreign = main.copy(engineId = "other")
        val failure = assertThrows(IllegalArgumentException::class.java) {
            EngineResources(profile(foreign), mapOf(foreign.packId to File("resources/other")))
        }
        assertEquals("Resource pack main belongs to other, not test", failure.message)
    }

    @Test fun foreignPackCannotReadADirectoryWithTheSamePackId() {
        val resources = EngineResources(profile(main), mapOf(main.packId to File("resources/main")))
        val foreign = main.copy(engineId = "other")

        val optionalFailure = assertThrows(IllegalArgumentException::class.java) { resources.directory(foreign) }
        val requiredFailure = assertThrows(IllegalArgumentException::class.java) { resources.requireDirectory(foreign) }
        assertEquals("Resource pack main belongs to other, not test", optionalFailure.message)
        assertEquals(optionalFailure.message, requiredFailure.message)
    }

    @Test fun undeclaredPackIsRejectedRatherThanReportedMissing() {
        val resources = EngineResources(profile(main), emptyMap())

        val optionalFailure = assertThrows(IllegalArgumentException::class.java) { resources.directory(models) }
        val requiredFailure = assertThrows(IllegalArgumentException::class.java) { resources.requireDirectory(models) }
        assertEquals("Resource pack models is not declared by engine test", optionalFailure.message)
        assertEquals(optionalFailure.message, requiredFailure.message)
    }

    @Test fun twoPacksResolveByIdRegardlessOfProfileOrMapOrder() {
        val mainDir = File("resources/main")
        val modelsDir = File("resources/models")
        for (packs in listOf(listOf(main, models), listOf(models, main))) {
            for (paths in listOf(
                linkedMapOf(main.packId to mainDir, models.packId to modelsDir),
                linkedMapOf(models.packId to modelsDir, main.packId to mainDir),
            )) {
                val resources = EngineResources(profile(*packs.toTypedArray()), paths)
                assertSame(mainDir, resources.requireDirectory(main.copy()))
                assertSame(modelsDir, resources.requireDirectory(models.copy()))
            }
        }
    }

    @Test fun declaredPackMayBeAbsentButRequiredLookupIdentifiesTheMissingDirectory() {
        val resources = EngineResources(profile(main), emptyMap())
        assertNull(resources.directory(main))

        val failure = assertThrows(IllegalStateException::class.java) { resources.requireDirectory(main) }
        assertEquals("Missing resource directory for pack main (engine test)", failure.message)
    }

    @Test fun profileWithoutResourcePacksAcceptsAnEmptyCollection() {
        val resources = EngineResources(profile(), emptyMap())
        assertEquals("test", resources.engineId)
        assertTrue(resources.directories.isEmpty())
    }

    @Test fun pathsAreNotInspectedNormalizedOrVerified() {
        val path = object : File("not-created/../main") {
            override fun exists(): Boolean = error("must not inspect resource paths")
            override fun isDirectory(): Boolean = error("must not inspect resource paths")
            override fun getCanonicalFile(): File = error("must not resolve resource paths")
            override fun getCanonicalPath(): String = error("must not resolve resource paths")
        }
        val resources = EngineResources(profile(main), mapOf(main.packId to path))
        assertSame(path, resources.directory(main))
        assertSame(path, resources.requireDirectory(main))
    }

    private fun profile(vararg packs: ResourcePackSpec) = object : GameProfile {
        override val id = "test"
        override val displayNameRes = 0
        override val iconRes = 0
        override val gamePackages = emptyList<String>()
        override val display = DisplaySpec(1280, 720)
        override val resourcePacks = packs.toList()
        override val capabilities = emptySet<Capability>()
    }

    private data class Pack(override val packId: String, override val engineId: String) : ResourcePackSpec {
        override val relativeRoot = "resources/$packId"
        override val bundledAssetPrefix: String? = null
        override val requiresPrivilegedDelivery = false
        override fun readInstalledVersion(resourceDir: File): String? = error("must not read resource contents")
        override fun verifyInstalledFiles(resourceDir: File): String? = error("must not verify resource contents")
        override fun mapZipEntry(entryName: String): String? = error("must not inspect archives")
        override fun invalidateInstalledVersion(resourceDir: File) { error("must not change resource contents") }
        override fun checkCompatibility(manifestJson: String?): String? = error("must not check compatibility")
    }
}
