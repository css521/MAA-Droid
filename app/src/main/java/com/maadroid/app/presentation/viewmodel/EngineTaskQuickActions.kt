package com.maadroid.app.presentation.viewmodel

import com.maadroid.app.data.preferences.AppSettingsManager
import com.maadroid.app.domain.service.GameMuteCoordinator
import com.maadroid.app.manager.RemoteServiceManager

/** The same saved settings and audio recovery as Ark; the VM supplies the leased package. */
class EngineTaskQuickActions(
    private val settings: AppSettingsManager,
    private val audio: GameMuteCoordinator,
    private val screenOff: () -> Unit = {
        checkNotNull(RemoteServiceManager.getInstanceOrNull()) { "远程服务未连接" }.setDisplayPower(false)
    },
) {
    val mutedPackage get() = audio.mutedPackage
    val closeOnTaskEnd get() = settings.closeAppOnTaskEnd.value

    suspend fun onGameReady(packageName: String): Boolean =
        !settings.muteOnGameLaunch.value || audio.mutePackage(packageName)

    suspend fun toggleGameSound(packageName: String) = audio.togglePackage(packageName)
    suspend fun onGameClosed(packageName: String) = audio.unmutePackage(packageName)
    fun turnScreenOff() = screenOff()
}
