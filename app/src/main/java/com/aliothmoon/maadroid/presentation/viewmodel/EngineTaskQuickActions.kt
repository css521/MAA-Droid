package com.aliothmoon.maadroid.presentation.viewmodel

import com.aliothmoon.maadroid.data.preferences.AppSettingsManager
import com.aliothmoon.maadroid.domain.service.GameMuteCoordinator
import com.aliothmoon.maadroid.manager.RemoteServiceManager

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
