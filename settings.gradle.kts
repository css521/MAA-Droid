pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        gradlePluginPortal()
        mavenLocal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

includeBuild("build-logic")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven { url = uri("https://jitpack.io") }
        mavenCentral()
    }
}

rootProject.name = "MaaDroid"

// 模块按层分组，目录结构即依赖方向：
//   app -> engine:* -> engine:api -> core:* -> hidden-api
//
// 接入一个新游戏 = 在 engine/ 下加一个目录，照 engine/limbus 的样子实现
// GameProfile / AutomationEngine / EngineUi / ResourcePackSpec，
// 然后在 EngineSetup 里加一行 register。core:* 与既有引擎都不需要改动。
include(":app")

// 游戏引擎层：每个游戏一个模块，彼此不相依
include(":engine:api")
include(":engine:limbus")

// 平台能力层：与游戏无关，不得反向依赖 engine:*（由 ModuleBoundaryContractTest 钉住）
include(":core:bridge")
include(":core:remote")
// 引擎与宿主共用的 Compose 组件与主题。范围按「引擎实际需要」划，
// 不是把 app 的组件目录整个搬过来 —— 见 core/ui 的包注释
include(":core:ui")

// framework 隐藏 API 桩，只在编译期使用
include(":hidden-api")

// 构建期代码生成（偏好 KSP），不参与运行时依赖
include(":tooling:annotation-api")
include(":tooling:ksp-processor")
 
