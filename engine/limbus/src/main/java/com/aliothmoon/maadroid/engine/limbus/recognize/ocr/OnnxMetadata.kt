package com.aliothmoon.maadroid.engine.limbus.recognize.ocr

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Reads ModelProto.metadata_props (field 14), skipping weights without loading them into memory. */
internal object OnnxMetadata {
    fun read(file: File, key: String): String? = RandomAccessFile(file, "r").use { input ->
        val end = input.length()
        while (input.filePointer < end) {
            val tag = varint(input, end)
            require(tag ushr 3 != 0L) { "Invalid ONNX field" }
            if (tag == (14L shl 3 or 2L)) {
                val entryEnd = fieldEnd(input, end)
                var name: String? = null
                var value: String? = null
                while (input.filePointer < entryEnd) {
                    when (val entryTag = varint(input, entryEnd)) {
                        10L -> name = string(input, entryEnd)
                        18L -> value = string(input, entryEnd)
                        else -> skip(input, entryEnd, entryTag)
                    }
                }
                if (name == key) return@use value
            } else skip(input, end, tag)
        }
        null
    }

    private fun string(input: RandomAccessFile, end: Long): String {
        val valueEnd = fieldEnd(input, end)
        val size = valueEnd - input.filePointer
        require(size <= 1024 * 1024) { "ONNX metadata string too large" }
        val bytes = ByteArray(size.toInt())
        input.readFully(bytes)
        // ONNX strings are standard UTF-8. ORT 1.19 JNI NewStringUTF uses modified UTF-8,
        // which can corrupt/truncate this dictionary's supplementary-plane characters.
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun fieldEnd(input: RandomAccessFile, end: Long): Long {
        val size = varint(input, end)
        require(size >= 0 && size <= end - input.filePointer) { "Truncated ONNX field" }
        return input.filePointer + size
    }

    private fun skip(input: RandomAccessFile, end: Long, tag: Long) {
        require(tag ushr 3 != 0L) { "Invalid ONNX field" }
        when ((tag and 7).toInt()) {
            0 -> varint(input, end)
            1 -> advance(input, end, 8)
            2 -> input.seek(fieldEnd(input, end))
            5 -> advance(input, end, 4)
            else -> error("Unsupported ONNX wire type")
        }
    }

    private fun advance(input: RandomAccessFile, end: Long, count: Int) {
        require(count <= end - input.filePointer) { "Truncated ONNX field" }
        input.seek(input.filePointer + count)
    }

    private fun varint(input: RandomAccessFile, end: Long): Long {
        var result = 0L
        for (shift in 0..63 step 7) {
            require(input.filePointer < end) { "Truncated ONNX varint" }
            val byte = input.readUnsignedByte()
            require(shift != 63 || byte <= 1) { "Overflowing ONNX varint" }
            result = result or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) return result
        }
        error("Invalid ONNX varint")
    }
}
