package com.aliothmoon.maadroid.koin

import com.aliothmoon.maadroid.data.config.MaaPathConfig
import com.aliothmoon.maadroid.domain.usecase.AnalyzeTaskChainUseCase
import com.aliothmoon.maadroid.domain.usecase.CheckGameReadinessUseCase
import com.aliothmoon.maadroid.domain.usecase.PrepareTaskStartUseCase
import com.aliothmoon.maadroid.domain.usecase.SwitchCoreDataLocationUseCase
import com.aliothmoon.maadroid.manager.RemoteServiceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.dsl.module
import timber.log.Timber


val useCaseModule = module {
    factory { SwitchCoreDataLocationUseCase(get(), get()) }

    factory {
        AnalyzeTaskChainUseCase(
            taskChainState = get(),
            resourceDataManager = get(),
            activityManager = get(),
            depotRepository = get(),
            operBoxRepository = get(),
            itemHelper = get(),
            dropsRefresher = get(),
            appSettingsManager = get(),
            relocatePath = get<MaaPathConfig>()::toCorePath,
        )
    }
    factory {
        CheckGameReadinessUseCase(
            appAliveChecker = get(),
            appSettings = get(),
            achievementReporter = get(),
            isPackageInstalled = { packageName ->
                withContext(Dispatchers.IO) {
                    try {
                        RemoteServiceManager.getInstanceOrNull()
                            ?.isPackageInstalled(packageName) ?: true
                    } catch (e: Exception) {
                        Timber.w(e, "isPackageInstalled check failed for %s", packageName)
                        true
                    }
                }
            },
        )
    }
    factory {
        PrepareTaskStartUseCase(
            analyzeTaskChainUseCase = get(),
            checkGameReadiness = get(),
        )
    }
}
