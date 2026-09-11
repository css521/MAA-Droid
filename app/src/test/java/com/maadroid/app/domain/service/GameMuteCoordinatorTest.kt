package com.maadroid.app.domain.service

import com.maadroid.app.data.preferences.AppSettingsManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameMuteCoordinatorTest {

    @Test
    fun unmuteRestoresAudioAndClearsMarker() = runBlocking {
        val fixture = fixture(initialMarker = GAME_PACKAGE)

        assertTrue(fixture.coordinator.unmute())

        assertEquals(listOf(AudioRequest(GAME_PACKAGE, muted = false)), fixture.audio.requests)
        assertEquals("", fixture.persisted.value)
        assertFalse(fixture.coordinator.isMuted.value)
        fixture.close()
    }

    @Test
    fun unmuteFailureKeepsMarkerAndRetriesOnReconnect() = runBlocking {
        val fixture = fixture(initialMarker = GAME_PACKAGE)
        fixture.audio.result = false

        assertFalse(fixture.coordinator.unmute())
        assertEquals(GAME_PACKAGE, fixture.persisted.value)
        assertTrue(fixture.coordinator.isMuted.value)

        fixture.audio.result = true
        fixture.coordinator.startAutoRestore()
        fixture.audio.connected.value = true
        yield()

        assertEquals(AudioRequest(GAME_PACKAGE, muted = false), fixture.audio.requests.last())
        assertEquals("", fixture.persisted.value)
        fixture.close()
    }

    @Test
    fun reconnectRestoresPersistedMarker() = runBlocking {
        val fixture = fixture(initialMarker = GAME_PACKAGE)

        fixture.coordinator.startAutoRestore()
        fixture.audio.connected.value = true
        yield()

        assertEquals(listOf(AudioRequest(GAME_PACKAGE, muted = false)), fixture.audio.requests)
        assertEquals("", fixture.persisted.value)
        fixture.close()
    }

    @Test
    fun reconnectWithoutMarkerSendsNoRequest() = runBlocking {
        val fixture = fixture(initialMarker = "")

        fixture.coordinator.startAutoRestore()
        fixture.audio.connected.value = true
        yield()

        assertTrue(fixture.audio.requests.isEmpty())
        fixture.close()
    }

    @Test
    fun muteReassertsAudioForMarkedPackage() = runBlocking {
        val fixture = fixture(initialMarker = GAME_PACKAGE)

        assertTrue(fixture.coordinator.mute(CLIENT_TYPE))

        assertEquals(listOf(AudioRequest(GAME_PACKAGE, muted = true)), fixture.audio.requests)
        assertEquals(GAME_PACKAGE, fixture.persisted.value)
        fixture.close()
    }

    @Test
    fun muteFailureKeepsMarkerForSelfHeal() = runBlocking {
        val fixture = fixture(initialMarker = "")
        fixture.audio.result = false

        assertFalse(fixture.coordinator.mute(CLIENT_TYPE))

        assertEquals(GAME_PACKAGE, fixture.persisted.value)
        assertTrue(fixture.coordinator.isMuted.value)
        fixture.close()
    }

    @Test
    fun toggleMutesWhenUnmarkedAndRestoresWhenMarked() = runBlocking {
        val fixture = fixture(initialMarker = "")

        assertTrue(fixture.coordinator.toggle(CLIENT_TYPE))
        assertEquals(GAME_PACKAGE, fixture.persisted.value)

        assertTrue(fixture.coordinator.toggle(CLIENT_TYPE))
        assertEquals("", fixture.persisted.value)
        assertEquals(
            listOf(
                AudioRequest(GAME_PACKAGE, muted = true),
                AudioRequest(GAME_PACKAGE, muted = false),
            ),
            fixture.audio.requests,
        )
        fixture.close()
    }

    private fun fixture(initialMarker: String): Fixture {
        val persisted = MutableStateFlow(initialMarker)
        val manager = mockk<AppSettingsManager>()
        every { manager.initialMutedGamePackage } returns initialMarker
        coEvery { manager.setMutedGamePackage(any()) } coAnswers { persisted.value = firstArg() }
        val audio = FakeGameAudioAdapter()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = GameMuteCoordinator(manager, audio, scope)
        return Fixture(persisted, audio, coordinator, scope)
    }

    @Test
    fun enginePackageUsesTheSamePersistentMuteAndRestoreFlow() = runBlocking {
        val fixture = fixture(initialMarker = "")
        val pkg = "com.ProjectMoon.LimbusCompany"
        assertTrue(fixture.coordinator.togglePackage(pkg))
        assertEquals(pkg, fixture.persisted.value)
        assertEquals(pkg, fixture.coordinator.mutedPackage.value)
        assertTrue(fixture.coordinator.togglePackage(pkg))
        assertEquals("", fixture.persisted.value)
        assertEquals(listOf(AudioRequest(pkg, true), AudioRequest(pkg, false)), fixture.audio.requests)
        fixture.close()
    }

    @Test
    fun switchingGamesRestoresOldPackageBeforeMutingNewOne() = runBlocking {
        val fixture = fixture(initialMarker = GAME_PACKAGE)
        val pkg = "com.ProjectMoon.LimbusCompany"
        assertTrue(fixture.coordinator.mutePackage(pkg))
        assertEquals(listOf(AudioRequest(GAME_PACKAGE, false), AudioRequest(pkg, true)), fixture.audio.requests)
        assertTrue(fixture.coordinator.unmutePackage(GAME_PACKAGE))
        assertEquals(pkg, fixture.persisted.value)
        assertEquals(2, fixture.audio.requests.size)
        fixture.close()
    }

    @Test
    fun failedOldPackageRestoreKeepsMarkerAndDoesNotMuteNextGame() = runBlocking {
        val fixture = fixture(initialMarker = GAME_PACKAGE)
        fixture.audio.result = false
        assertFalse(fixture.coordinator.mutePackage("com.ProjectMoon.LimbusCompany"))
        assertEquals(GAME_PACKAGE, fixture.persisted.value)
        assertEquals(listOf(AudioRequest(GAME_PACKAGE, false)), fixture.audio.requests)
        fixture.close()
    }

    private data class Fixture(
        val persisted: MutableStateFlow<String>,
        val audio: FakeGameAudioAdapter,
        val coordinator: GameMuteCoordinator,
        val scope: CoroutineScope,
    ) {
        fun close() = scope.cancel()
    }

    private class FakeGameAudioAdapter : GameAudioAdapter {
        override val connected = MutableStateFlow(false)
        val requests = mutableListOf<AudioRequest>()
        var result = true

        override suspend fun setMuted(packageName: String, muted: Boolean): Boolean {
            requests += AudioRequest(packageName, muted)
            return result
        }
    }

    private data class AudioRequest(
        val packageName: String,
        val muted: Boolean,
    )

    private companion object {
        const val CLIENT_TYPE = "Official"
        const val GAME_PACKAGE = "com.hypergryph.arknights"
    }
}
