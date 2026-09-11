plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    id("com.maadroid.app.i18n-verify")
}

android {
    namespace = "com.maadroid.app.ui"
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
    // compose BOM / ui / material3 由 engine:api 以 api 暴露，这里复用同一套版本约束，
    // 避免宿主、引擎、core:ui 三处各自声明版本而漂移
    api(project(":engine:api"))
    // UiText 的类型定义在 core:common；这里只加 @Composable 取值器
    api(project(":core:common"))

    implementation(libs.androidx.core.ktx)
    // FloatWindowEditText 用 AppCompatEditText：悬浮窗里系统输入法弹不出来，
    // 要靠它的 extract mode 走另一条输入路径
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.ui.tooling.preview)

    testImplementation(libs.junit)
}
