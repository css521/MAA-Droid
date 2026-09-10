package com.aliothmoon.maadroid.engine.limbus

import com.aliothmoon.maadroid.engine.AutomationEngine
import com.aliothmoon.maadroid.engine.EngineProvider
import com.aliothmoon.maadroid.engine.EngineUi
import com.aliothmoon.maadroid.engine.GameProfile
import com.aliothmoon.maadroid.engine.limbus.ui.LimbusUi

/** The module supplies its profile, runtime factory and UI without App-specific bindings. */
object LimbusEngineProvider : EngineProvider {
    override val profile: GameProfile = LimbusProfile
    override val ui: EngineUi = LimbusUi

    /** Native caches and event streams belong to each run, never to the shared provider. */
    override fun createEngine(): AutomationEngine = LimbusEngine()
}
