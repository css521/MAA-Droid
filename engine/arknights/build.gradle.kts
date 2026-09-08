plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.aliothmoon.maadroid.engine.arknights"
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
    api(project(":engine:api"))

    testImplementation(libs.junit)
}
