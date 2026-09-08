import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
    id("com.aliothmoon.maadroid.asset-manifest")
    id("com.aliothmoon.maadroid.i18n-verify")
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(localPropertiesFile.inputStream())
    }
}

// 见 version.properties：本仓库是从 MAA-Meow（688 提交）切出的独立仓库，
// 提交数从 1 重新开始，必须叠加基线否则 versionCode 回退、已装用户无法升级
val versionProps = Properties().apply {
    rootProject.file("version.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val versionCodeBase = versionProps.getProperty("versionCodeBase", "0").trim().toInt()
val versionNameFallback = versionProps.getProperty("versionNameFallback", "0.0.0-dev").trim()

val gitVersionCode: Int by lazy {
    val commits = providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().toIntOrNull() ?: 0
    versionCodeBase + commits
}

val gitVersionName: String by lazy {
    val desc = providers.exec {
        commandLine("git", "describe", "--tags", "--always")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
    val match =
        Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.]+))?(?:-(\d+)-g[0-9a-f]+)?$""").matchEntire(
            desc
        )
    if (match != null) {
        val (major, minor, patch, pre, distance) = match.destructured
        when {
            distance.isEmpty() && pre.isEmpty() -> "$major.$minor.$patch"
            distance.isEmpty() -> "$major.$minor.$patch-$pre"
            else -> "$major.$minor.${patch.toInt() + 1}-alpha.$distance"
        }
    } else {
        // 无 tag 时 describe --always 返回短 hash，解析不出语义化版本，用基线兜底
        versionNameFallback
    }
}

// 本机默认只编 arm64、关 LTO；CI=true 保持双 ABI + LTO
// -Pmaa.abi=all|arm64-v8a|x86_64 或 local.properties 覆盖
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
println("[ABI] ${nativeAbis.joinToString()}")
println("[native] LTO=$nativeLto")
println("[Java Version] ${System.getProperty("java.version")}")

android {
    namespace = "com.aliothmoon.maadroid"
    compileSdk = 37


    defaultConfig {
        applicationId = "com.aliothmoon.maameow"
        minSdk = 28
        targetSdk = 36
        versionCode = gitVersionCode
        versionName = gitVersionName
        println("Build version: versionCode=$versionCode, versionName=$versionName")
        ndkVersion = "29.0.13113456"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // setup_maa_core.py deploy 时写入 .maaversion；缺失时为空串，运行时检查宽松放行
        val maaCoreVersion = rootProject.file(".maaversion")
            .takeIf { it.isFile }?.readText()?.trim().orEmpty()
        buildConfigField("String", "MAA_CORE_VERSION", "\"$maaCoreVersion\"")

        ndk {
            abiFilters.addAll(nativeAbis)
        }
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
                ?: localProperties.getProperty("KEYSTORE_PATH", "")
            if (keystorePath.isNotEmpty()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                    ?: localProperties.getProperty("KEYSTORE_PASSWORD", "")
                keyAlias = System.getenv("KEY_ALIAS")
                    ?: localProperties.getProperty("KEY_ALIAS", "")
                keyPassword = System.getenv("KEY_PASSWORD")
                    ?: localProperties.getProperty("KEY_PASSWORD", "")
            }
        }
    }

    buildTypes {
        val minifyProguardFiles = listOf(
            getDefaultProguardFile("proguard-android-optimize.txt").absolutePath,
            "proguard-rules.pro",
        )
        // local.properties: maa.debugR8=true 时 debug 也走 R8
        val debugR8 = localProperties.getProperty("maa.debugR8", "false").toBoolean()
        getByName("debug") {
            isMinifyEnabled = debugR8
            isShrinkResources = debugR8
            if (debugR8) {
                proguardFiles(*minifyProguardFiles.toTypedArray())
                println("[R8] debug minify+shrink enabled (maa.debugR8=true)")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(*minifyProguardFiles.toTypedArray())
            val keystorePath = System.getenv("KEYSTORE_PATH")
                ?: localProperties.getProperty("KEYSTORE_PATH", "")
            if (keystorePath.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
                println("[Signing] Using release keystore: $keystorePath")
            } else {
                println("[Signing] No release keystore configured, release build will not be signed")
            }
        }
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        aidl = true
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true

            // 显式声明冲突取舍，不让 AGP 隐式选择（未来版本会直接报错）：
            //
            // libonnxruntime.so —— 两份不同构建冲突：
            //   · MaaCore 带的 26.3 MB，被 libMaaCore.so 硬链接（已用 llvm-readelf 确认
            //     DT_NEEDED 含 libonnxruntime.so），换掉会让方舟引擎加载失败
            //   · onnxruntime-android AAR 带的 17.6 MB，配套其 libonnxruntime4j_jni.so
            //   取舍：保留 app 模块 jniLibs 里 MaaCore 的那份 —— 方舟不能回退是硬约束。
            //   ONNX 的 C API 经 OrtGetApiBase()->GetApi(version) 协商版本，Java 绑定
            //   在版本不支持时会明确报错而非静默出错；边狱侧的 ONNX 推理需真机验证。
            //
            // libc++_shared.so —— core-bridge（NDK 29）与 OpenCV AAR 各带一份。
            //   取舍：以 NDK 提供的为准（setup_maa_core.py 的 EXCLUDE_SO 也是同一原则：
            //   "provided by the NDK toolchain, never ship MAA's copy"）。
            pickFirsts += setOf(
                "**/libonnxruntime.so",
                "**/libc++_shared.so",
            )
        }
        resources {
            pickFirsts += setOf(
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md"
            )
        }
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        localeFilters += listOf("zh", "en")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core-remote"))
    // 各游戏引擎。:app 是唯一依赖全部引擎的模块（装配点）
    implementation(project(":engine-limbus"))
    compileOnly(project(":hidden-api"))
    implementation(project(":annotation-api"))
    ksp(project(":ksp-processor"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.exifinterface)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.window)

    // Koin
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // Third-party
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(libs.fastjson2)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.libsu)
    implementation(libs.device.compat)
    implementation(libs.xx.permissions)
    implementation(libs.floatingx)
    implementation(libs.sonner)
    implementation(libs.timber)
    implementation(libs.okhttp)
    implementation(libs.angus.mail)
    implementation(libs.angus.activation)
    implementation(libs.jakarta.activation.api)
    implementation(libs.reorderable)
    implementation(libs.compose.markdown)

    // sora-editor：JSON 语法高亮编辑器（TextMate + darcula 主题）
    implementation(platform(libs.bom))
    implementation(libs.editor)
    implementation(libs.editor.language.textmate)

    // Kotlin Serialization
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.xzakota.focus.api)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.koin.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

abstract class GenerateAchievementStringResTask : DefaultTask() {

    @get:InputFile
    abstract val stringsFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /**
     * 生成代码所在包。必须是受追踪的输入：写死在 action 里时，改包名不会让任务失效，
     * Gradle 会判为 UP-TO-DATE 而把旧包的产物留在源集中，编译报 Unresolved reference 'R'。
     */
    @get:Input
    abstract val packageName: Property<String>

    private val nameRe = Regex("^achievement_([A-Za-z0-9]+)_(title|desc|condition)$")

    @TaskAction
    fun generate() {
        val doc = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(stringsFile.get().asFile)
        val nodes = doc.getElementsByTagName("string")

        val byField = linkedMapOf(
            "title" to sortedMapOf<String, String>(),
            "desc" to sortedMapOf(),
            "condition" to sortedMapOf(),
        )
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            val m = nameRe.matchEntire(el.getAttribute("name")) ?: continue
            byField.getValue(m.groupValues[2])[m.groupValues[1]] = m.value
        }

        val code = buildString {
            appendLine("// AUTO-GENERATED by generate*AchievementStringRes. DO NOT EDIT.")
            appendLine("package ${packageName.get()}")
            appendLine()
            appendLine("import ${packageName.get().substringBeforeLast(".data.achievement")}.R")
            appendLine()
            appendLine("internal fun achievementStringResId(id: String, field: AchievementField): Int = when (field.key) {")
            for ((field, entries) in byField) {
                appendLine("    \"$field\" -> when (id) {")
                for ((id, resName) in entries) {
                    appendLine("        \"$id\" -> R.string.$resName")
                }
                appendLine("        else -> 0")
                appendLine("    }")
            }
            appendLine("    else -> 0")
            appendLine("}")
        }

        val pkgDir = outputDir.get().asFile.resolve(packageName.get().replace('.', '/'))
        pkgDir.mkdirs()
        pkgDir.resolve("AchievementStringResGenerated.kt").writeText(code)
        logger.lifecycle("Generated achievementStringResId: ${byField.values.sumOf { it.size }} entries")
    }
}

androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercaseChar() }
        val genTask = tasks.register(
            "generate${variantName}AchievementStringRes",
            GenerateAchievementStringResTask::class.java,
        ) {
            description =
                "Generate achievement string-resource lookup (avoids Resources.getIdentifier)"
            group = "build"
            stringsFile.set(layout.projectDirectory.file("src/main/res/values/strings.xml"))
            packageName.set("com.aliothmoon.maadroid.data.achievement")
        }
        variant.sources.kotlin?.addGeneratedSourceDirectory(genTask) { it.outputDir }
    }
}
