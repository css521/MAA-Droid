package com.aliothmoon.maadroid.engine.limbus.resource

import java.io.DataInputStream
import java.io.File
import java.util.zip.CRC32

/** Checks PNG chunk structure/CRC without decoding pixels or allocating an image. */
internal object PngTemplateContract {
    private val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

    fun validate(file: File) {
        try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                val header = ByteArray(8).also(input::readFully)
                require(header.contentEquals(signature)) { "PNG 签名不匹配" }
                var remaining = file.length() - 8
                var first = true
                var imageData = false
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    require(remaining >= 12) { "PNG 缺少完整结束块" }
                    val size = input.readInt().toLong() and 0xffffffffL
                    require(size <= remaining - 12) { "PNG 数据块被截断" }
                    val type = ByteArray(4).also(input::readFully)
                    val name = type.toString(Charsets.US_ASCII)
                    require(type.all { it.toInt() in 65..90 || it.toInt() in 97..122 }) { "PNG 块类型无效" }
                    if (first) require(name == "IHDR" && size == 13L) { "PNG 缺少图像头" }
                    else require(name != "IHDR") { "PNG 图像头重复" }
                    val crc = CRC32().also { it.update(type) }
                    if (first) {
                        val info = ByteArray(13).also(input::readFully)
                        crc.update(info)
                        DataInputStream(info.inputStream()).use { dimensions ->
                            require(dimensions.readInt() > 0 && dimensions.readInt() > 0) { "PNG 尺寸无效" }
                            val depth = dimensions.readUnsignedByte()
                            val color = dimensions.readUnsignedByte()
                            val depths = when (color) {
                                0 -> setOf(1, 2, 4, 8, 16)
                                2, 4, 6 -> setOf(8, 16)
                                3 -> setOf(1, 2, 4, 8)
                                else -> emptySet()
                            }
                            require(depth in depths && dimensions.readUnsignedByte() == 0 &&
                                dimensions.readUnsignedByte() == 0 && dimensions.readUnsignedByte() in 0..1) {
                                "PNG 图像格式无效"
                            }
                        }
                    } else {
                        var unread = size
                        while (unread > 0) {
                            val count = minOf(unread, buffer.size.toLong()).toInt()
                            input.readFully(buffer, 0, count)
                            crc.update(buffer, 0, count)
                            unread -= count
                        }
                    }
                    require((input.readInt().toLong() and 0xffffffffL) == crc.value) { "PNG 数据块校验失败" }
                    remaining -= size + 12
                    if (name == "IDAT" && size > 0) imageData = true
                    if (name == "IEND") {
                        require(size == 0L && imageData && remaining == 0L) { "PNG 图像数据或结束块无效" }
                        break
                    }
                    first = false
                }
            }
        } catch (failure: Exception) {
            throw IllegalArgumentException("无效 PNG 模板 ${file.name}：${failure.message}", failure)
        }
    }
}
