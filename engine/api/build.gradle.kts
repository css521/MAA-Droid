plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.aliothmoon.maadroid.engineapi"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // 设备侧抽象（FrameSource / InputSink / DeviceControl）由 core-remote 实现，
    // 但契约本身只需要 core-bridge 里的 EngineIds 等标识
    api(project(":core:bridge"))

    // EngineUi 暴露 @Composable 插槽，宿主据此渲染引擎面板而不认识引擎
    // 必须是 api：ui/material3 的版本由 BOM 约束，消费者（engine-*）也要拿到
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.ui)
    api(libs.androidx.material3)
    api(libs.androidx.annotation)
    // EngineEvent 事件流
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
