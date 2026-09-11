package com.maadroid.app.engine.limbus.recognize.ocr

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Reads declared interfaces and metadata, skipping weights without loading them into memory. */
internal object OnnxMetadata {
    data class Tensor(val elementType: Long, val dimensions: List<Long?>)
    data class ValueInfo(val name: String, val tensor: Tensor?)
    data class ModelInterface(val inputs: List<ValueInfo>, val outputs: List<ValueInfo>)

    /** Read GraphProto's declared inputs/outputs; tensor weights and operator bodies are skipped. */
    fun readModelInterface(file: File): ModelInterface = RandomAccessFile(file, "r").use { input ->
        val end = input.length()
        var irVersion: Long? = null
        var graph: ModelInterface? = null
        while (input.filePointer < end) {
            when (val tag = varint(input, end)) {
                8L -> irVersion = varint(input, end)
                58L -> {
                    require(graph == null) { "Duplicate ONNX graph" }
                    graph = graphInterface(input, fieldEnd(input, end))
                }
                else -> skip(input, end, tag)
            }
        }
        require(irVersion != null && irVersion > 0) { "Missing ONNX IR version" }
        requireNotNull(graph) { "Missing ONNX graph" }
    }

    private fun graphInterface(input: RandomAccessFile, end: Long): ModelInterface {
        val inputs = mutableListOf<ValueInfo>()
        val outputs = mutableListOf<ValueInfo>()
        while (input.filePointer < end) {
            when (val tag = varint(input, end)) {
                90L, 98L -> {
                    val tensors = if (tag == 90L) inputs else outputs
                    require(tensors.size < 64) { "Too many ONNX inputs or outputs" }
                    val tensor = valueInfo(input, fieldEnd(input, end))
                    require(tensors.none { it.name == tensor.name }) { "Duplicate ONNX tensor name" }
                    tensors += tensor
                }
                else -> skip(input, end, tag)
            }
        }
        require(inputs.isNotEmpty() && outputs.isNotEmpty()) { "ONNX graph has no input/output declarations" }
        return ModelInterface(inputs, outputs)
    }

    private fun valueInfo(input: RandomAccessFile, end: Long): ValueInfo {
        var name: String? = null
        var type: Pair<Long, List<Long?>>? = null
        var hasType = false
        while (input.filePointer < end) {
            when (val tag = varint(input, end)) {
                10L -> name = string(input, end)
                18L -> { type = valueType(input, fieldEnd(input, end)); hasType = true }
                else -> skip(input, end, tag)
            }
        }
        require(!name.isNullOrBlank() && hasType) { "ONNX value lacks name or type" }
        // Only the first output is consumed by our models. Auxiliary sequence/map outputs
        // may be valid and must not force an otherwise compatible model to be rejected.
        return ValueInfo(name, type?.let { Tensor(it.first, it.second) })
    }

    private fun valueType(input: RandomAccessFile, end: Long): Pair<Long, List<Long?>>? {
        var tensor: Pair<Long, List<Long?>>? = null
        while (input.filePointer < end) {
            when (val tag = varint(input, end)) {
                10L -> tensor = tensorType(input, fieldEnd(input, end))
                else -> skip(input, end, tag)
            }
        }
        return tensor
    }

    private fun tensorType(input: RandomAccessFile, end: Long): Pair<Long, List<Long?>> {
        var elementType: Long? = null
        var dimensions: List<Long?>? = null
        while (input.filePointer < end) {
            when (val tag = varint(input, end)) {
                8L -> elementType = varint(input, end)
                18L -> dimensions = tensorShape(input, fieldEnd(input, end))
                else -> skip(input, end, tag)
            }
        }
        require(elementType != null && dimensions != null) { "ONNX tensor lacks element type or shape" }
        return elementType to dimensions
    }

    private fun tensorShape(input: RandomAccessFile, end: Long): List<Long?> {
        val dimensions = mutableListOf<Long?>()
        while (input.filePointer < end) {
            when (val tag = varint(input, end)) {
                10L -> {
                    require(dimensions.size < 16) { "ONNX tensor rank exceeds supported limit" }
                    val dimEnd = fieldEnd(input, end)
                    var dimension: Long? = null
                    while (input.filePointer < dimEnd) {
                        when (val dimTag = varint(input, dimEnd)) {
                            8L -> dimension = varint(input, dimEnd).also {
                                require(it >= 0) { "Negative ONNX tensor dimension" }
                            }
                            18L -> { string(input, dimEnd); dimension = null }
                            else -> skip(input, dimEnd, dimTag)
                        }
                    }
                    dimensions += dimension
                }
                else -> skip(input, end, tag)
            }
        }
        return dimensions
    }

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
