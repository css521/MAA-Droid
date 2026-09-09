package com.aliothmoon.maadroid.engine.arknights.resource

import java.io.File

/** Android MaaCore uses ncnn; the ONNX files from upstream alone are not usable. */
object MaaOcrResources {
    fun missingFiles(resource: File): List<String> {
        val roots = buildList {
            add(resource)
            File(resource, "global").listFiles()?.filter { it.isDirectory }?.forEach {
                add(File(it, "resource"))
            }
        }
        val modelDirs = linkedSetOf(
            File(resource, "PaddleOCR/det"), File(resource, "PaddleOCR/rec"),
            File(resource, "PaddleCharOCR/det"), File(resource, "PaddleCharOCR/rec"),
        )
        for (root in roots) {
            for (pack in listOf("PaddleOCR", "PaddleCharOCR")) {
                for (kind in listOf("det", "rec")) {
                    File(root, "$pack/$kind").takeIf { it.isDirectory }?.let(modelDirs::add)
                }
            }
        }
        return modelDirs.flatMap { dir ->
            buildList {
                add(File(dir, "${dir.name}.ncnn.param"))
                add(File(dir, "${dir.name}.ncnn.bin"))
                if (dir.name == "rec") add(File(dir, "keys.txt"))
            }
        }.filter { !it.isFile || it.length() == 0L }
            .map { it.relativeTo(resource).invariantSeparatorsPath }
    }
}
