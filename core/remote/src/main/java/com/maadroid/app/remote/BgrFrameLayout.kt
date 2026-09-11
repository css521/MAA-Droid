package com.maadroid.app.remote

/** Validate before constructing a Frame or handing mapped bytes to native recognition. */
object BgrFrameLayout {
    fun byteCount(meta: LongArray, width: Int, height: Int, capacity: Int): Int {
        require(meta.size >= 4) { "截图元数据不完整" }
        require(meta[0] == width.toLong() && meta[1] == height.toLong()) {
            "截图尺寸 ${meta[0]}x${meta[1]} 与要求 ${width}x$height 不符"
        }
        require(width > 0 && height > 0 && meta[2] in (width.toLong() * 3)..Int.MAX_VALUE.toLong()) {
            "无效 BGR 行距: ${meta[2]}"
        }
        require(meta[3] > 0) { "截图尚无实际首帧" }
        val bytes = meta[2] * height
        require(bytes <= capacity) { "BGR 截图长度 $bytes 超出帧映射容量 $capacity" }
        return bytes.toInt()
    }
}
