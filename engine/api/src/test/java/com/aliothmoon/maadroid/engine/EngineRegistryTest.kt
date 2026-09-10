package com.aliothmoon.maadroid.engine

import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

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

    @Test fun registeringTheSameProviderInstanceIsIdempotent() {
        val provider = Provider("test", listOf(Pack("main", "test", "downloads/main")))
        EngineRegistry.register(provider)
        EngineRegistry.register(provider)

        assertEquals(listOf(provider.profile), EngineRegistry.profiles())
        assertEquals(provider.profile.resourcePacks, EngineRegistry.allResourcePacks())
        assertSame(provider, EngineRegistry.provider("test"))
        assertTrue(provider.created.isEmpty())
    }

    @Test fun duplicateEngineIdIsRejectedWithoutReplacingOrReleasingExistingEngines() {
        val old = Provider("test")
        val replacement = Provider("test")
        EngineRegistry.register(old)
        val first = EngineRegistry.createEngine("test")!!
        assertRegistrationRejected(replacement, "Engine id already registered: test")
        val next = EngineRegistry.createEngine("test")!!
        assertSame(old, EngineRegistry.provider("test"))
        assertSame(old.created.last(), next)
        assertNotSame(first, next)
        assertTrue(replacement.created.isEmpty())
        assertEquals(0, old.created.first().releaseCount)
        first.release()
        next.release()
    }

    @Test fun thirdProviderHasIndependentFactoriesEventStreamsAndResourceDirectories() {
        val first = Provider("first", listOf(Pack("first-main", "first", "cache/resource")))
        val second = Provider("second", listOf(Pack("second-main", "second", "engines/second")))
        val third = Provider("third", listOf(
            Pack("third-main", "third", "downloads/third/main"),
            Pack("third-models", "third", "downloads/third/models"),
        ))
        val providers = listOf(first, second, third)
        providers.forEach(EngineRegistry::register)

        assertEquals(providers.map { it.profile }, EngineRegistry.profiles())
        assertEquals(providers.flatMap { it.profile.resourcePacks }, EngineRegistry.allResourcePacks())
        assertTrue(providers.all { it.created.isEmpty() })

        val engines = providers.map { EngineRegistry.createEngine(it.profile.id)!! }
        val thirdNext = EngineRegistry.createEngine("third")!!
        try {
            for ((index, engine) in engines.withIndex()) {
                assertSame(providers[index].profile, engine.profile)
                assertNotSame(engine, thirdNext)
                assertNotSame(engine.events, thirdNext.events)
            }
            assertSame(third.profile, thirdNext.profile)
            assertEquals(listOf(1, 1, 2), providers.map { it.created.size })
        } finally {
            (engines + thirdNext).forEach { it.release() }
        }

        val dataRoot = createTempDirectory("third-engine-resources").toFile()
        try {
            val packs = EngineRegistry.allResourcePacks()
            for (pack in packs) {
                val dir = File(dataRoot, pack.relativeRoot)
                assertTrue(dir.mkdirs())
                File(dir, "version.txt").writeText(pack.packId)
            }
            // Invalidating the third game's main pack leaves all other packs (including its models) intact.
            val thirdMain = third.profile.resourcePacks.first()
            thirdMain.invalidateInstalledVersion(File(dataRoot, thirdMain.relativeRoot))
            for (pack in packs) {
                assertEquals(
                    pack.packId,
                    if (pack === thirdMain) null else pack.packId,
                    pack.readInstalledVersion(File(dataRoot, pack.relativeRoot)),
                )
            }
        } finally {
            dataRoot.deleteRecursively()
        }
    }

    @Test fun providersWithoutResourcePacksAreAllowed() {
        val first = Provider("first")
        val second = Provider("second")
        EngineRegistry.register(first)
        EngineRegistry.register(second)

        assertEquals(listOf(first.profile, second.profile), EngineRegistry.profiles())
        assertTrue(EngineRegistry.allResourcePacks().isEmpty())
    }

    @Test fun blankEngineIdsAreRejected() {
        for (id in listOf("", " ", "\t")) {
            assertRegistrationRejected(Provider(id), "Engine id must not be blank")
        }
    }

    @Test fun resourcePackMustBelongToItsProfile() {
        assertRegistrationRejected(
            Provider("test", listOf(Pack("main", "another", "resources/test"))),
            "belongs to another, not test",
        )
    }

    @Test fun blankPackIdsAreRejected() {
        for (id in listOf("", " ", "\t")) {
            assertRegistrationRejected(
                Provider("test", listOf(Pack(id, "test", "resources/test"))),
                "Resource pack id must not be blank",
            )
        }
    }

    @Test fun packIdsAreUniqueAcrossProviders() {
        EngineRegistry.register(Provider("first", listOf(Pack("shared", "first", "resources/first"))))
        assertRegistrationRejected(
            Provider("second", listOf(Pack("shared", "second", "resources/second"))),
            "Resource pack id already registered: shared",
        )
    }

    @Test fun packIdsAreUniqueWithinOneProvider() {
        assertRegistrationRejected(
            Provider("test", listOf(
                Pack("shared", "test", "resources/main"),
                Pack("shared", "test", "resources/models"),
            )),
            "Resource pack id already registered: shared",
        )
    }

    @Test fun equalNestedAndAliasedRootsAreRejectedAcrossProviders() {
        EngineRegistry.register(Provider("first", listOf(Pack("first-main", "first", "resources/main"))))
        for (root in listOf("resources/main", "resources/main/models", "resources", "./resources//main/")) {
            assertRegistrationRejected(
                Provider("second", listOf(Pack("second-main", "second", root))),
                "overlaps resources/main",
            )
        }
    }

    @Test fun normalizedExistingRootsAlsoBlockAliasesAndChildren() {
        val pack = Pack("first-main", "first", "./resources//main/")
        EngineRegistry.register(Provider("first", listOf(pack)))
        assertSame(pack, EngineRegistry.allResourcePacks().single())
        assertEquals("./resources//main/", pack.relativeRoot)
        for (root in listOf("resources/main", "resources/main/models")) {
            assertRegistrationRejected(
                Provider("second", listOf(Pack("second-main", "second", root))),
                "overlaps resources/main",
            )
        }
    }

    @Test fun equalAndNestedRootsAreRejectedWithinOneProvider() {
        for (root in listOf("resources/main", "resources/main/models", "resources", "./resources//main/")) {
            assertRegistrationRejected(
                Provider("test", listOf(
                    Pack("main", "test", "resources/main"),
                    Pack("models", "test", root),
                )),
                "overlaps resources/main",
            )
        }
    }

    @Test fun siblingPrefixesAndRootsWithoutAnEngineScopeAreAllowed() {
        val first = Provider("first", listOf(Pack("main", "first", "cache/resource")))
        val second = Provider("second", listOf(
            Pack("extra", "second", "cache/resource-extra"),
            Pack("models", "second", "models/模型"),
        ))
        EngineRegistry.register(first)
        EngineRegistry.register(second)

        assertEquals(first.profile.resourcePacks + second.profile.resourcePacks, EngineRegistry.allResourcePacks())
    }

    @Test fun rootsMustBeNonEmptyRelativeDirectoriesWithoutParentTraversal() {
        val invalidRoots = listOf(
            "", " ", "/resources", "//server/resources", ".", "./", "././",
            "..", "../resources", "resources/..", "resources/../other", "resources/../../other",
            "resources\\models", "C:/resources", "C:resources", "resources/\u0000models",
        )
        for (root in invalidRoots) {
            assertRegistrationRejected(
                Provider("test", listOf(Pack("main", "test", root))),
                "Resource root",
            )
        }
    }

    @Test fun aLaterInvalidPackDoesNotPublishOrReserveAnyPartOfTheProvider() {
        val original = Provider("original", listOf(Pack("original-main", "original", "cache/resource")))
        EngineRegistry.register(original)
        val main = Pack("candidate-main", "candidate", "downloads/main")
        val invalid = Provider("candidate", listOf(
            main,
            Pack("candidate-models", "candidate", "cache/resource/models"),
        ))
        assertRegistrationRejected(invalid, "overlaps cache/resource")
        assertNull(EngineRegistry.createEngine("candidate"))

        // The corrected declaration must be able to reuse the first pack's id and directory.
        val corrected = Provider("candidate", listOf(
            main,
            Pack("candidate-models", "candidate", "downloads/models"),
        ))
        EngineRegistry.register(corrected)
        assertSame(corrected, EngineRegistry.provider("candidate"))
        assertEquals(listOf(original.profile, corrected.profile), EngineRegistry.profiles())
        assertEquals(original.profile.resourcePacks + corrected.profile.resourcePacks, EngineRegistry.allResourcePacks())
        assertSame(original.profile, EngineRegistry.resolveOrFallback(null))
    }

    @Test fun clearingForTestsAllowsReusingIdsAndRoots() {
        val pack = Pack("main", "test", "downloads/main")
        EngineRegistry.register(Provider("test", listOf(pack)))
        EngineRegistry.clearForTest()
        assertTrue(EngineRegistry.profiles().isEmpty())
        assertTrue(EngineRegistry.allResourcePacks().isEmpty())
        assertNull(EngineRegistry.provider("test"))
        assertNull(EngineRegistry.resolveOrFallback(null))

        val replacement = Provider("test", listOf(pack))
        EngineRegistry.register(replacement)
        assertSame(replacement, EngineRegistry.provider("test"))
        assertEquals(listOf(pack), EngineRegistry.allResourcePacks())
    }

    @Test fun unknownEngineHasNoFactory() {
        assertNull(EngineRegistry.createEngine("missing"))
    }

    private fun assertRegistrationRejected(provider: Provider, reason: String) {
        val profiles = EngineRegistry.profiles()
        val packs = EngineRegistry.allResourcePacks()
        val previous = EngineRegistry.provider(provider.profile.id)
        val fallback = EngineRegistry.resolveOrFallback(null)
        val error = assertThrows(IllegalArgumentException::class.java) { EngineRegistry.register(provider) }
        assertTrue("Expected '$reason' in '${error.message}'", error.message.orEmpty().contains(reason))
        assertEquals(profiles, EngineRegistry.profiles())
        assertEquals(packs, EngineRegistry.allResourcePacks())
        assertSame(previous, EngineRegistry.provider(provider.profile.id))
        assertSame(fallback, EngineRegistry.resolveOrFallback(null))
        assertTrue(provider.created.isEmpty())
    }

    private class Provider(engineId: String, packs: List<ResourcePackSpec> = emptyList()) : EngineProvider {
        override val profile = object : GameProfile {
            override val id = engineId
            override val displayNameRes = 0
            override val iconRes = 0
            override val gamePackages = emptyList<String>()
            override val display = DisplaySpec(1280, 720)
            override val resourcePacks = packs
            override val capabilities = emptySet<Capability>()
        }
        override val ui = object : EngineUi {
            override val taskPanels = emptyList<TaskPanelSpec>()
        }
        val created = mutableListOf<Engine>()
        override fun createEngine(): AutomationEngine = Engine(profile).also { created += it }
    }

    private data class Pack(
        override val packId: String,
        override val engineId: String,
        override val relativeRoot: String,
    ) : ResourcePackSpec {
        override val bundledAssetPrefix: String? = null
        override val requiresPrivilegedDelivery = false
        override fun readInstalledVersion(resourceDir: File): String? =
            File(resourceDir, "version.txt").takeIf { it.isFile }?.readText()
        override fun mapZipEntry(entryName: String): String? = null
        override fun invalidateInstalledVersion(resourceDir: File) {
            File(resourceDir, "version.txt").delete()
        }
        override fun checkCompatibility(manifestJson: String?): String? = null
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
