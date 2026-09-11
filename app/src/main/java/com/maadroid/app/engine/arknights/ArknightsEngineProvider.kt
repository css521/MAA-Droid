package com.maadroid.app.engine.arknights

import com.maadroid.app.data.preferences.AppSettingsManager
import com.maadroid.app.data.preferences.TaskChainState
import com.maadroid.app.engine.AutomationEngine
import com.maadroid.app.engine.EngineProvider
import com.maadroid.app.engine.EngineUi
import com.maadroid.app.engine.GameProfile
import com.maadroid.app.engine.TaskPanelSpec
import org.koin.core.Koin
import org.koin.core.context.GlobalContext

/** App-only bindings for Arknights; registration and engine creation also work before Koin starts. */
internal class ArknightsEngineProvider(
    private val koin: () -> Koin = { GlobalContext.get() },
) : EngineProvider {
    override val profile: GameProfile = ArknightsProfile

    override fun createEngine(): AutomationEngine = ArknightsEngine(
        resources = MaaResourcePreparation { resourceDir, options ->
            koin().get<MaaResourcePreparation>().prepare(resourceDir, options)
        },
        options = {
            val dependencies = koin()
            // ArknightsEngine calls this once in prepare and retains this immutable run snapshot.
            MaaRunOptions(
                clientType = dependencies.get<TaskChainState>().clientType,
                deployWithPause = dependencies.get<AppSettingsManager>().deployWithPause.value,
            )
        },
    )

    override val ui: EngineUi = object : EngineUi {
        // The legacy Arknights task page still owns its panels; this only connects the runtime.
        override val taskPanels: List<TaskPanelSpec> = emptyList()
    }
}
