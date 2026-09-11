package com.maadroid.app.koin

import com.maadroid.app.data.config.MaaPathConfig
import com.maadroid.app.domain.usecase.AnalyzeTaskChainUseCase
import com.maadroid.app.domain.usecase.CheckGameReadinessUseCase
import com.maadroid.app.domain.usecase.PrepareTaskStartUseCase
import com.maadroid.app.domain.usecase.SwitchCoreDataLocationUseCase
import com.maadroid.app.manager.RemoteServiceManager
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
