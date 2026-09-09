package com.aliothmoon.maadroid.buildlogic

/** Linker contracts read from llvm-readelf, rather than inferred from artifact version labels. */
internal object NativeRuntimeCompatibility {
    data class Symbol(val name: String, val version: String?, val defaultVersion: Boolean = false) {
        override fun toString() = name + (version?.let { "@$it" } ?: "")
    }

    data class Library(
        val label: String,
        val machine: String?,
        val elfClass: String?,
        val soname: String?,
        val needed: Set<String>,
        val imports: Set<Symbol>,
        val exports: Set<Symbol>,
    )

    private val symbolLine = Regex(
        """^\s*\d+:\s+[0-9a-fA-F]+\s+\d+\s+\S+\s+(GLOBAL|WEAK)\s+(DEFAULT|PROTECTED)\s+(\S+)\s+(\S+)""",
    )

    fun parse(label: String, output: String): Library {
        val imports = linkedSetOf<Symbol>()
        val exports = linkedSetOf<Symbol>()
        output.lineSequence().forEach { line ->
            val match = symbolLine.find(line) ?: return@forEach
            val section = match.groupValues[3]
            val name = match.groupValues[4]
            if (!name.startsWith("Ort")) return@forEach
            val symbol = Symbol(
                name.substringBefore('@'),
                name.substringAfter('@', "").trimStart('@').ifEmpty { null },
                "@@" in name,
            )
            if (section == "UND") imports += symbol else exports += symbol
        }
        fun header(key: String) = Regex("(?m)^\\s*$key:\\s*(.+)$").find(output)?.groupValues?.get(1)?.trim()
        return Library(
            label, header("Machine"), header("Class"),
            Regex("""\(SONAME\).*\[([^]]+)]""").find(output)?.groupValues?.get(1),
            Regex("""\(NEEDED\).*\[([^]]+)]""").findAll(output).map { it.groupValues[1] }.toSet(),
            imports, exports,
        )
    }

    fun verify(consumer: Library, runtime: Library) {
        check(runtime.soname == "libonnxruntime.so") { "${runtime.label}: unexpected ONNX Runtime SONAME ${runtime.soname}" }
        check(runtime.soname in consumer.needed) { "${consumer.label} does not link ${runtime.soname}; review native dependencies" }
        check(consumer.machine != null && consumer.machine == runtime.machine &&
            consumer.elfClass == "ELF64" && consumer.elfClass == runtime.elfClass) {
            "Native architecture mismatch: ${consumer.label} vs ${runtime.label}"
        }
        check(consumer.imports.any { it.name == "OrtGetApiBase" }) {
            "${consumer.label}: missing OrtGetApiBase import; review native dependencies"
        }
        val missing = consumer.imports.filter { required ->
            runtime.exports.none { exported ->
                exported.name == required.name && if (required.version == null) {
                    exported.version == null || exported.defaultVersion
                } else exported.version == required.version
            }
        }
        check(missing.isEmpty()) {
            "ONNX Runtime ABI mismatch: ${consumer.label} requires ${missing.joinToString()}, " +
                "but ${runtime.label} exports ${runtime.exports.joinToString()}. " +
                "Choose a matching onnxruntime version in gradle/libs.versions.toml; " +
                "do not bypass this check with packaging pickFirsts."
        }
    }
}
