plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.aliothmoon.maadroid.engine.limbus"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
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
    api(project(":engine-api"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.annotation)
    // 模板匹配与图像预处理（上游全部识别都建立在 OpenCV 之上）
    implementation(libs.opencv)
    // ONNX：三个分类模型 + PP-OCR 的 det/rec
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
