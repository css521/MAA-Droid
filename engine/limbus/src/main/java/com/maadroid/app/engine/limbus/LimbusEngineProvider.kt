package com.maadroid.app.engine.limbus

import com.maadroid.app.engine.AutomationEngine
import com.maadroid.app.engine.EngineProvider
import com.maadroid.app.engine.EngineUi
import com.maadroid.app.engine.GameProfile
import com.maadroid.app.engine.limbus.ui.LimbusUi

/** The module supplies its profile, runtime factory and UI without App-specific bindings. */
object LimbusEngineProvider : EngineProvider {
    override val profile: GameProfile = LimbusProfile
    override val ui: EngineUi = LimbusUi

    /** Native caches and event streams belong to each run, never to the shared provider. */
    override fun createEngine(): AutomationEngine = LimbusEngine()
}
