package com.aliothmoon.maadroid.koin

import android.app.Application
import android.content.Context
import com.aliothmoon.maadroid.domain.launch.LaunchPipeline
import org.junit.Test
import org.koin.dsl.module
import org.koin.test.verify.definition
import org.koin.test.verify.injectedParameters
import org.koin.test.verify.verify
import com.aliothmoon.maadroid.data.config.MaaPathConfig
import com.aliothmoon.maadroid.data.resource.ActivityManager

/**
 * 静态校验 Koin 依赖图，防止运行期 NoDefinitionFound 启动崩溃
 * 模块列表与 MaaApplication.startKoin 保持一致
 */
class AppModuleVerifyTest {

    @Test
    fun allModules_dependencyGraph_isClosed() {
        val all = module { includes(appModule, useCaseModule, viewModelModule, floatingWindowModule) }
        all.verify(
            extraTypes = listOf(
                Application::class,
                Context::class,
                androidx.datastore.core.DataStore::class,
            ),
            // 构造器里由模块**内联提供**的参数，静态校验需显式放行
            injections = injectedParameters(
                definition<LaunchPipeline>(Function0::class, Function2::class),
                // isCoreSeparated：在装配点从 AppSettingsManager 读成布尔值再传入，
                // 这样方舟侧的 MaaPathConfig 不必认识宿主的设置类
                definition<MaaPathConfig>(Boolean::class),
                // clientTypeProvider：以 provider 注入，破 data/resource ↔ TaskChainState 的环
                definition<ActivityManager>(Function0::class),
            ),
        )
    }
}
