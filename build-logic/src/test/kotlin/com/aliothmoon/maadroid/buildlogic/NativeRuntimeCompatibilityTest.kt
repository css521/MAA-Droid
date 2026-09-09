package com.aliothmoon.maadroid.buildlogic

import org.junit.Assert.*
import org.junit.Test

class NativeRuntimeCompatibilityTest {
    // The 735 phone logs: JNI requires VERS_1.20.0, but MaaCore's selected runtime exports 1.19.2.
    @Test fun rejectsTheVersionMismatchFromThePhoneLogs() {
        val failure = assertThrows(IllegalStateException::class.java) {
            NativeRuntimeCompatibility.verify(consumer("1.20.0"), runtime("1.19.2"))
        }
        assertTrue(failure.message!!.contains("OrtGetApiBase@VERS_1.20.0"))
        assertTrue(failure.message!!.contains("OrtGetApiBase@VERS_1.19.2"))
    }

    @Test fun acceptsTheMatchingSharedRuntime() {
        NativeRuntimeCompatibility.verify(consumer("1.19.2"), runtime("1.19.2"))
    }

    @Test fun doesNotHardcodeTheCurrentUpstreamVersion() {
        NativeRuntimeCompatibility.verify(consumer("1.30.1"), runtime("1.30.1"))
    }

    @Test fun checksAdditionalProviderSymbols() {
        val consumer = consumer("1.19.2", "6: 0000000000000000 0 FUNC GLOBAL DEFAULT UND OrtMissingProvider@VERS_1.19.2")
        val failure = assertThrows(IllegalStateException::class.java) {
            NativeRuntimeCompatibility.verify(consumer, runtime("1.19.2"))
        }
        assertTrue(failure.message!!.contains("OrtMissingProvider"))
    }

    @Test fun rejectsAnAbiInTheWrongDirectory() {
        assertThrows(IllegalStateException::class.java) {
            NativeRuntimeCompatibility.verify(consumer("1.19.2"), runtime("1.19.2").copy(machine = "Advanced Micro Devices X86-64"))
        }
    }

    @Test fun requiresAnActualLinkDependency() {
        assertThrows(IllegalStateException::class.java) {
            NativeRuntimeCompatibility.verify(consumer("1.19.2").copy(needed = emptySet()), runtime("1.19.2"))
        }
    }

    @Test fun cannotPassWithoutSymbolsOrHeaders() {
        assertThrows(IllegalStateException::class.java) {
            NativeRuntimeCompatibility.verify(NativeRuntimeCompatibility.parse("empty", ""), runtime("1.19.2"))
        }
        assertThrows(IllegalStateException::class.java) {
            NativeRuntimeCompatibility.verify(consumer("1.19.2").copy(imports = emptySet()), runtime("1.19.2"))
        }
    }

    @Test fun unversionedImportsNeedADefaultExport() {
        val original = consumer("1.19.2")
        val unversioned = original.copy(imports = original.imports.map { it.copy(version = null) }.toSet())
        NativeRuntimeCompatibility.verify(unversioned, runtime("1.19.2"))
        val noDefault = runtime("1.19.2").let { lib ->
            lib.copy(exports = lib.exports.map { it.copy(defaultVersion = false) }.toSet())
        }
        assertThrows(IllegalStateException::class.java) {
            NativeRuntimeCompatibility.verify(unversioned, noDefault)
        }
    }

    private fun consumer(version: String, extra: String = "") = NativeRuntimeCompatibility.parse("JNI", """
        Class: ELF64
        Machine: AArch64
        0x0000000000000001 (NEEDED) Shared library: [libonnxruntime.so]
        0x0000000000000001 (NEEDED) Shared library: [libc.so]
        5: 0000000000000000 0 FUNC GLOBAL DEFAULT UND OrtGetApiBase@VERS_$version (3)
        $extra
    """.trimIndent())

    private fun runtime(version: String) = NativeRuntimeCompatibility.parse("shared ORT", """
        Class: ELF64
        Machine: AArch64
        0x000000000000000e (SONAME) Library soname: [libonnxruntime.so]
        208: 00000000004a6214 12 FUNC GLOBAL DEFAULT 16 OrtGetApiBase@@VERS_$version
    """.trimIndent())
}
