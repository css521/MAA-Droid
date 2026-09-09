package com.aliothmoon.maadroid.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File

// 生成 MaaResource 目录内全部文件的 JSON 清单
abstract class GenerateAssetManifestTask : DefaultTask() {
    @get:InputDirectory
    @get:Optional
    abstract val sourceDir: DirectoryProperty

    @get:OutputFile
    abstract val manifestFile: RegularFileProperty

    @get:Input
    abstract val assetSourceDir: Property<String>

    @TaskAction
    fun generate() {
        val source = sourceDir.orNull?.asFile
        val manifest = manifestFile.get().asFile

        validateAndroidResources(source)
        manifest.parentFile?.mkdirs()

        val files = if (source?.exists() == true) {
            listFilesRecursively(source, "")
                .map { "${assetSourceDir.get()}/$it" }
                .sorted()
        } else {
            emptyList()
        }

        val jsonContent = """{"files":[${files.joinToString(",") { "\"$it\"" }}]}"""
        manifest.writeText(jsonContent)
        logger.lifecycle("Generated asset manifest: ${files.size} files")
    }

    private fun validateAndroidResources(source: File?) {
        if (source == null || !File(source, "version.json").isFile) {
            throw GradleException("MAA resources missing. Run python scripts/setup_maa_core.py first.")
        }
        // Android MaaCore loads ncnn, even though the upstream archive ships ONNX.
        // Check both the base models and every shipped language before producing an APK.
        val modelDirs = linkedSetOf(
            File(source, "PaddleOCR/det"), File(source, "PaddleOCR/rec"),
            File(source, "PaddleCharOCR/det"), File(source, "PaddleCharOCR/rec"),
        )
        source.walkTopDown().filter {
            it.isDirectory && it.name in setOf("det", "rec") &&
                it.parentFile.name in setOf("PaddleOCR", "PaddleCharOCR")
        }.forEach(modelDirs::add)
        val missing = modelDirs.flatMap { dir ->
            buildList {
                add(File(dir, "${dir.name}.ncnn.param"))
                add(File(dir, "${dir.name}.ncnn.bin"))
                if (dir.name == "rec") add(File(dir, "keys.txt"))
            }
        }.filter { !it.isFile || it.length() == 0L }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Android MaaCore OCR resources missing or empty:\n" +
                    missing.joinToString("\n") { "  ${it.relativeTo(source)}" } +
                    "\nRun python scripts/convert_ocr_ncnn.py --resource ${source.path} --cache .maa-cache/ncnn" +
                    "\nInstall scripts/requirements.txt first. --skip-ncnn cannot produce a runnable APK.",
            )
        }
    }

    private fun listFilesRecursively(dir: File, basePath: String): List<String> {
        val result = mutableListOf<String>()
        dir.listFiles()?.forEach { file ->
            val relativePath = if (basePath.isEmpty()) file.name else "$basePath/${file.name}"
            if (file.isDirectory) {
                result.addAll(listFilesRecursively(file, relativePath))
            } else {
                result.add(relativePath)
            }
        }
        return result
    }
}

class AssetManifestPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val generateAssetManifest = project.tasks.register(
            "generateAssetManifest",
            GenerateAssetManifestTask::class.java,
        ) {
            description = "Generate assets file manifest"
            group = "build"

            val assetsDir = project.layout.projectDirectory.dir("src/main/assets")
            val assetSourceDirName = "MaaSync/MaaResource"
            // 检查 MaaSync/MaaResource 目录
            doFirst {
                val targetDir = File(assetsDir.asFile, assetSourceDirName)
                if (!targetDir.exists()) {
                    logger.lifecycle("Creating directory: ${targetDir.absolutePath}")
                    targetDir.mkdirs()
                } else {
                    logger.lifecycle("Directory already exists: ${targetDir.absolutePath}")
                }
            }

            assetSourceDir.set(assetSourceDirName)
            sourceDir.set(assetsDir.dir(assetSourceDirName))
            manifestFile.set(assetsDir.file("MaaSync/asset_manifest.json"))
        }

        project.tasks.matching { it.name.startsWith("preBuild") }.configureEach {
            dependsOn(generateAssetManifest)
        }
    }
}
