plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.aliothmoon.maadroid.engine.arknights"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":engine:api"))
    implementation(project(":core:remote"))
    implementation(libs.jna) { artifact { type = "aar" } }

    testImplementation(libs.junit)
}
