plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    // 引擎自带任务面板（EngineUi.taskPanels）。compose 依赖由 engine:api 以 api 暴露，
    // 所以引擎写面板**不需要依赖 :app** —— 这正是「加一个游戏只加一个模块」的前提
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.aliothmoon.maadroid.engine.limbus"
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":engine:api"))
    implementation(project(":core:ui"))
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.annotation)
    // 模板匹配与图像预处理（上游全部识别都建立在 OpenCV 之上）
    implementation(libs.opencv)
    // Shared with MaaCore. Keep the C runtime and Java/JNI binding at the same version;
    // the APK build verifies MaaCore's versioned symbols against the resolved AAR.
    implementation(libs.onnxruntime.android) {
        version { strictly(libs.versions.onnxruntime.get()) }
    }

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
