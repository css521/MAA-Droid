package com.aliothmoon.maadroid.engine.arknights

import com.aliothmoon.maadroid.data.preferences.AppSettingsManager
import com.aliothmoon.maadroid.data.preferences.TaskChainState
import com.aliothmoon.maadroid.engine.AutomationEngine
import com.aliothmoon.maadroid.engine.EngineProvider
import com.aliothmoon.maadroid.engine.EngineUi
import com.aliothmoon.maadroid.engine.GameProfile
import com.aliothmoon.maadroid.engine.TaskPanelSpec
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
