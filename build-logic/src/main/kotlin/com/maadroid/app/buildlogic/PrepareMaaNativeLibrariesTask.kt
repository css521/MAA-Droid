package com.maadroid.app.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject

/** Stage MaaCore without a duplicate ORT only after validating the shared Android C/JNI pair. */
@CacheableTask
abstract class PrepareMaaNativeLibrariesTask @Inject constructor(
    private val exec: ExecOperations,
) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    @get:Input
    abstract val abis: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeAar: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val readelf: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun prepare() {
        val source = sourceDir.get().asFile
        val destination = outputDir.get().asFile
        check(!source.canonicalFile.toPath().startsWith(destination.canonicalFile.toPath()) &&
            !destination.canonicalFile.toPath().startsWith(source.canonicalFile.toPath())) {
            "Generated JNI directory must not overlap upstream downloads"
        }
        val selected = abis.get()
        require(selected.isNotEmpty() && selected.all { it in setOf("arm64-v8a", "x86_64") }) {
            "Supported MaaCore ABIs: arm64-v8a, x86_64"
        }
        val aar = runtimeAar.files.singleOrNull()?.takeIf { it.extension == "aar" }
            ?: error("Expected one resolved onnxruntime-android AAR, found ${runtimeAar.files}")
        val sources = mutableListOf<Pair<String, List<File>>>()
        ZipFile(aar).use { archive ->
            for (abi in selected) {
                val libraries = File(source, abi).listFiles { file -> file.isFile && file.extension == "so" }
                    ?.sortedBy { it.name }.orEmpty()
                val core = libraries.singleOrNull { it.name == "libMaaCore.so" }
                    ?: error("MaaCore missing for $abi. Run scripts/setup_maa_core.py --abi $abi")
                fun extract(name: String): File {
                    val entry = archive.getEntry("jni/$abi/$name") ?: error("$aar does not contain $abi/$name")
                    return File(temporaryDir, "$abi/$name").also { file ->
                        file.parentFile.mkdirs()
                        archive.getInputStream(entry).use { input -> file.outputStream().use(input::copyTo) }
                    }
                }
                val runtime = inspect(extract("libonnxruntime.so"), "$abi/${aar.name}/libonnxruntime.so")
                NativeRuntimeCompatibility.verify(inspect(core, "$abi/libMaaCore.so"), runtime)
                NativeRuntimeCompatibility.verify(
                    inspect(extract("libonnxruntime4j_jni.so"), "$abi/libonnxruntime4j_jni.so"), runtime,
                )
                // Future upstream libraries may also use ORT; validate those before sharing it.
                libraries.filter { it != core && it.name != "libonnxruntime.so" }.forEach { library ->
                    val metadata = inspect(library, "$abi/${library.name}")
                    if ("libonnxruntime.so" in metadata.needed) NativeRuntimeCompatibility.verify(metadata, runtime)
                }
                sources += abi to libraries.filter { it.name != "libonnxruntime.so" }
                logger.lifecycle("[ONNX] $abi: MaaCore and Java/JNI symbols match ${aar.name}")
            }
        }
        // Downloaded files remain intact. APK packaging receives the runtime only from the AAR.
        check(!destination.exists() || destination.deleteRecursively()) { "Cannot clear staged MaaCore JNI files" }
        sources.forEach { (abi, libraries) ->
            libraries.forEach { library ->
                val target = File(destination, "$abi/${library.name}")
                target.parentFile.mkdirs()
                library.copyTo(target)
            }
        }
    }

    private fun inspect(file: File, label: String): NativeRuntimeCompatibility.Library {
        val output = ByteArrayOutputStream()
        exec.exec {
            commandLine(readelf.get().asFile.absolutePath, "--file-header", "--dynamic", "--dyn-syms", "--wide", file.absolutePath)
            standardOutput = output
        }.assertNormalExitValue()
        return NativeRuntimeCompatibility.parse(label, output.toString(Charsets.UTF_8))
    }
}
