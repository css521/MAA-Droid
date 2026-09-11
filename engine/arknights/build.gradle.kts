import com.android.build.api.dsl.LibraryExtension
import com.maadroid.app.buildlogic.PrepareMaaNativeLibrariesTask
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    id("com.maadroid.app.asset-manifest")
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

// 与宿主和 core:bridge 使用相同的 ABI 选择，单 ABI 部署无需准备另一份 MaaCore。
val ci = System.getenv("CI")?.equals("true", ignoreCase = true) == true
val abiRaw = (findProperty("maa.abi") as String?)?.trim().orEmpty()
    .ifEmpty { localProperties.getProperty("maa.abi", "").trim() }
    .ifEmpty { if (ci) "all" else "arm64-v8a" }
val nativeAbis: List<String> = if (abiRaw.equals("all", ignoreCase = true)) {
    listOf("arm64-v8a", "x86_64")
} else {
    abiRaw.split(',', ' ').map(String::trim).filter(String::isNotEmpty)
}

android {
    namespace = "com.maadroid.app.engine.arknights"
    compileSdk = 37
    ndkVersion = "29.0.13113456"

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")

        // setup_maa_core.py 写入部署版本；缺失时仍为空串，保持既有兼容检查语义。
        val maaCoreVersion = rootProject.file(".maaversion")
            .takeIf { it.isFile }?.readText()?.trim().orEmpty()
        buildConfigField("String", "MAA_CORE_VERSION", "\"$maaCoreVersion\"")

        ndk {
            abiFilters.addAll(nativeAbis)
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

extensions.configure<LibraryExtension> {
    // Use AGP 9's public source-set DSL; the legacy accessor casts to the old interface.
    // Preserve upstream downloads; package only JNI files staged after ORT ABI validation.
    sourceSets.getByName("main").jniLibs.setSrcDirs(emptyList<String>())
}

dependencies {
    api(project(":engine:api"))
    implementation(project(":core:remote"))
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(libs.kotlinx.serialization.json)
    // Both engines require the same complete ONNX Android C/JNI pair. The strict
    // constraint also propagates to the consuming APK; conflicting versions must fail.
    implementation(libs.onnxruntime.android) {
        version { strictly(libs.versions.onnxruntime.get()) }
    }

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercaseChar() }
        val prepareNative = tasks.register<PrepareMaaNativeLibrariesTask>("prepare${variantName}MaaNativeLibraries") {
            sourceDir.set(layout.projectDirectory.dir("src/main/jniLibs"))
            abis.set(nativeAbis)
            // Inspect this variant's resolved AAR; its strict version is shared with the APK.
            runtimeAar.from(variant.runtimeConfiguration.incoming.artifactView {
                componentFilter {
                    it is ModuleComponentIdentifier && it.group == "com.microsoft.onnxruntime" &&
                        it.module == "onnxruntime-android"
                }
            }.files)
            val host = when {
                System.getProperty("os.name").startsWith("Mac") -> "darwin-x86_64"
                System.getProperty("os.name").startsWith("Windows") -> "windows-x86_64"
                else -> "linux-x86_64"
            }
            val suffix = if (host.startsWith("windows")) ".exe" else ""
            readelf.set(sdkComponents.ndkDirectory.map {
                it.file("toolchains/llvm/prebuilt/$host/bin/llvm-readelf$suffix")
            })
            outputDir.set(layout.buildDirectory.dir("generated/maa-native/${variant.name}/jniLibs"))
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(prepareNative) { it.outputDir }
    }
}
