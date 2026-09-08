import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    // AIDL parcelable（PermissionGrantRequest / PermissionStateInfo）用 @Parcelize
    alias(libs.plugins.kotlin.parcelize)
}

// ABI 与 LTO 由根项目统一决定：本机默认只编 arm64、关 LTO；CI=true 保持双 ABI + LTO。
// native 代码住在本模块，所以开关必须在这里落地（app 只声明 abiFilters 无法约束本模块）。
// -Pmaa.abi=all|arm64-v8a|x86_64 或 local.properties 覆盖
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val ci = System.getenv("CI")?.equals("true", ignoreCase = true) == true
val abiRaw = (findProperty("maa.abi") as String?)?.trim().orEmpty()
    .ifEmpty { localProperties.getProperty("maa.abi", "").trim() }
    .ifEmpty { if (ci) "all" else "arm64-v8a" }
val nativeAbis: List<String> = if (abiRaw.equals("all", ignoreCase = true)) {
    listOf("arm64-v8a", "x86_64")
} else {
    abiRaw.split(',', ' ').map(String::trim).filter(String::isNotEmpty)
}
val nativeLto = when (
    (findProperty("maa.nativeLto") as String?)?.trim()
        ?: localProperties.getProperty("maa.nativeLto", "")
) {
    "true" -> true
    "false" -> false
    else -> ci
}
println("[core-bridge][ABI] ${nativeAbis.joinToString()}  LTO=$nativeLto")

android {
    // namespace 只用于生成 R 与解析 manifest 相对类名；模块内 Kotlin/Java 包名
    // 沿用 com.aliothmoon.maadroid.*，这样 app 侧 import 无需改动
    namespace = "com.aliothmoon.maadroid.corebridge"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        ndkVersion = "29.0.13113456"

        ndk {
            abiFilters.addAll(nativeAbis)
        }
        @Suppress("UnstableApiUsage")
        externalNativeBuild {
            cmake {
                arguments(
                    "-DANDROID_STL=c++_shared",
                    "-DMAA_NATIVE_LTO=${if (nativeLto) "ON" else "OFF"}",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/native/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // framework 隐藏 API：提权进程里直接调系统服务
    compileOnly(project(":hidden-api"))
    // scrcpy 派生代码里的 @NonNull / @RequiresApi
    implementation(libs.androidx.annotation)
}
