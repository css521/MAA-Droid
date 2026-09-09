package com.aliothmoon.maadroid.presentation.view.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aliothmoon.maadroid.R
import com.aliothmoon.maadroid.engine.EngineExecutionCoordinator
import com.aliothmoon.maadroid.engine.EngineRegistry
import com.aliothmoon.maadroid.engine.resource.EngineResourceService
import com.aliothmoon.maadroid.presentation.view.engine.EngineResourceCard
import org.koin.compose.koinInject

/** Downloadable engine packs share Home with the existing Arknights updater. */
@Composable
internal fun HomeEngineResources() {
    val profiles = remember {
        EngineRegistry.profiles().filter { profile ->
            profile.resourcePacks.any { it.upstreamArchive != null }
        }
    }
    if (profiles.isEmpty()) return
    val service = koinInject<EngineResourceService>()
    val actions = viewModel(key = "home_engine_resources") { HomeEngineResourcesViewModel(service) }
    // Covers preparation and running even when their task page is not composed.
    val activeEngineId by EngineExecutionCoordinator.shared.activeEngineId.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        profiles.forEach { profile ->
            key(profile.id) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 12.dp)) {
                        Text(
                            stringResource(R.string.home_engine_resources, stringResource(profile.displayNameRes)),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        profile.resourcePacks.filter { it.upstreamArchive != null }.forEach { pack ->
                            key(pack.packId) {
                                EngineResourceCard(
                                    pack, service, running = activeEngineId != null,
                                    onAction = { actions.perform(pack) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
