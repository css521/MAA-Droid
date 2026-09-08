plugins {
    alias(libs.plugins.android.library)
    // 手势模型（UnlockGesture 等）跨进程以 JSON 传递
    alias(libs.plugins.kotlin.serialization)
}

android {
    // 包名沿用 com.aliothmoon.maadroid.*，app 侧 import 无需改动
    namespace = "com.aliothmoon.maadroid.coreremote"
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
    api(project(":core-bridge"))
    // 实现 engine-api 的设备侧抽象（DeviceHandle / FrameSource / InputSink）
    api(project(":engine-api"))
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
