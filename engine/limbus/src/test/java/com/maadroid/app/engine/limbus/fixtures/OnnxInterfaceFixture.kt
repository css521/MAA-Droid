package com.maadroid.app.engine.limbus.fixtures

/** Minimal protobuf declarations for interface validation; no operators/weights, not an inference model. */
internal object OnnxInterfaceFixture {
    fun model(
        input: List<Long?> = listOf(null, 3, 10, 10),
        output: List<Long?> = listOf(null, 1),
        dictionary: String? = null,
        elementType: Long = 1,
        graphPrefix: ByteArray = byteArrayOf(),
        graphSuffix: ByteArray = byteArrayOf(),
    ): ByteArray = number(1, 10) + field(7, graphPrefix +
        field(11, tensor("image", input, elementType)) + field(12, tensor("result", output, elementType)) + graphSuffix) +
        (dictionary?.let { entry("character", it) } ?: byteArrayOf())

    fun entry(key: String, value: String) = field(14, field(1, key.toByteArray()) + field(2, value.toByteArray()))

    fun sequenceOutput(name: String): ByteArray {
        val scalarTensorType = field(1, number(1, 1) + field(2, byteArrayOf()))
        val sequenceType = field(4, field(1, scalarTensorType))
        return field(12, field(1, name.toByteArray()) + field(2, sequenceType))
    }

    private fun tensor(name: String, dims: List<Long?>, elementType: Long): ByteArray {
        val shape = dims.flatMapIndexed { index, dim ->
            field(1, dim?.let { number(1, it) } ?: field(2, "dynamic_$index".toByteArray())).toList()
        }.toByteArray()
        val type = number(1, elementType) + field(2, shape)
        return field(1, name.toByteArray()) + field(2, field(1, type))
    }

    fun field(number: Int, bytes: ByteArray) = varint(number * 8L + 2) + varint(bytes.size.toLong()) + bytes
    fun number(field: Int, value: Long) = varint(field * 8L) + varint(value)
    private fun varint(number: Long): ByteArray {
        var value = number
        val bytes = ArrayList<Byte>()
        do {
            bytes += ((value and 127) or if (value ushr 7 != 0L) 128 else 0).toByte()
            value = value ushr 7
        } while (value != 0L)
        return bytes.toByteArray()
    }
}
