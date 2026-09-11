plugins {
    alias(libs.plugins.android.library)
}
android{
    compileSdk = 36

    namespace = "com.maadroid.app.hidden_api"

    defaultConfig {
        minSdk = 27
    }

    compileOptions {
        targetCompatibility = JavaVersion.VERSION_17
        sourceCompatibility = JavaVersion.VERSION_17
    }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
