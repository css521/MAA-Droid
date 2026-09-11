plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.maadroid.app.common"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // 刻意**不依赖 Compose**：这里是与 UI 无关的通用能力（i18n 文本模型、
    // 将来的偏好/日志/网络）。UiText 的 @Composable 取值器放在 core:ui，
    // 因为非 UI 层（如 FightConfig、ActivityManager）也要构造 UiText，
    // 不该为此背上 Compose 依赖
    implementation(libs.androidx.annotation)

    testImplementation(libs.junit)
}
