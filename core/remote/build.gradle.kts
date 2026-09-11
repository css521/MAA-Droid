plugins {
    alias(libs.plugins.android.library)
    // 手势模型（UnlockGesture 等）跨进程以 JSON 传递
    alias(libs.plugins.kotlin.serialization)
}

android {
    // 包名沿用 com.maadroid.app.*，app 侧 import 无需改动
    namespace = "com.maadroid.app.coreremote"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // AIDL 契约、native 桥、输入注入、scrcpy 派生封装
    api(project(":core:bridge"))
    // 实现 engine-api 的设备侧抽象（DeviceHandle / FrameSource / InputSink）
    api(project(":engine:api"))
    compileOnly(project(":hidden-api"))

    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.serialization.json)
    // 提权进程与 App 进程都要落盘/读配置
    implementation(libs.libsu)
    // SystemServiceHelper：提权进程里按名取系统服务 binder
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.timber)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
}

/**
 * ModuleBoundaryContractTest 直接读**其它模块**磁盘上的源码来检查依赖方向。
 *
 * Gradle 默认只把本模块的源码算作该测试任务的输入，于是往 engine/ 里引入一处违规后
 * 本任务会被判为 UP-TO-DATE 而不重跑，报出一个 **stale PASS** —— CI 绿着，
 * 违规却已经进去了。实测确认过这个行为，故把被扫描的目录显式声明为输入。
 */
tasks.withType<Test>().configureEach {
    inputs.files(
        fileTree(rootDir.resolve("engine")) {
            include("*/src/main/**/*.kt", "*/src/main/**/*.java")
        },
        fileTree(rootDir.resolve("core")) {
            include("*/src/main/**/*.kt", "*/src/main/**/*.java")
        },
    )
        .withPropertyName("architectureScanSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
