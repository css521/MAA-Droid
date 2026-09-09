package com.aliothmoon.maadroid.engine.limbus.recognize.ocr

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class OnnxMetadataTest {
    @Test fun readsUtf8DictionaryWithoutLosingSupplementaryCharactersOrTheirIndices() = withModel(
        field(7, ByteArray(2000)) + entry("other", "ignored") + entry("character", "a\n𠀀\n中\n🐖"),
    ) {
        assertEquals(listOf("a", "𠀀", "中", "🐖"), CtcDecoder.parseCharacters(OnnxMetadata.read(it, "character")!!))
    }

    @Test fun missingMetadataReturnsNull() = withModel(field(7, ByteArray(10))) {
        assertNull(OnnxMetadata.read(it, "character"))
    }

    @Test fun rejectsTruncatedAndOverflowingLengthsInsteadOfReadingPastEntry() {
        for (bytes in listOf(entry("character", "abc").dropLast(1).toByteArray(),
            byteArrayOf(114, 127), byteArrayOf(114) + ByteArray(10) { 0xff.toByte() },
            byteArrayOf(114, 2, 18, 3))) {
            withModel(bytes) { assertTrue(runCatching { OnnxMetadata.read(it, "character") }.isFailure) }
        }
    }

    @Test fun skipsUnknownFieldsButRejectsInvalidUtf8() {
        withModel(field(14, byteArrayOf(24, 1) + field(2, "中".toByteArray()) + field(1, "character".toByteArray()))) {
            assertEquals("中", OnnxMetadata.read(it, "character"))
        }
        withModel(field(14, field(1, "character".toByteArray()) + field(2, byteArrayOf(0xff.toByte())))) {
            assertTrue(runCatching { OnnxMetadata.read(it, "character") }.isFailure)
        }
    }

    private fun entry(key: String, value: String) = field(14, field(1, key.toByteArray()) + field(2, value.toByteArray()))
    private fun field(number: Int, bytes: ByteArray) = varint(number * 8 + 2) + varint(bytes.size) + bytes
    private fun varint(number: Int): ByteArray {
        var value = number
        val bytes = ArrayList<Byte>()
        do {
            bytes += ((value and 127) or if (value > 127) 128 else 0).toByte()
            value = value ushr 7
        } while (value != 0)
        return bytes.toByteArray()
    }
    private fun withModel(bytes: ByteArray, test: (File) -> Unit) {
        val file = File.createTempFile("onnx-metadata", ".onnx")
        try { file.writeBytes(bytes); test(file) } finally { file.delete() }
    }
}
