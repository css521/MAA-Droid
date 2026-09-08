package com.aliothmoon.maadroid.engine.limbus.recognize

import kotlin.math.exp

/**
 * 分类器的前后处理。
 *
 * 单独抽成纯函数不是洁癖：onnxruntime 的 native 库在纯 JVM 单测里加载不了，
 * 若把归一化与解码埋在推理调用内部，这些**算错了不会报错、只会给出错误标签**的
 * 逻辑就只能靠真机验证。与 [TemplateMatcher.mergeNearby] 同样的取舍。
 */
object ClassifierMath {

    /**
     * ImageNet 归一化常量，与上游 `nn_classifier._preprocess_image` 一致。
     * 三个模型都是 mobilenet_v3_small 微调而来，用的是训练时的同一组值 ——
     * 换成 0.5/0.5 这类「通用」值会让分类结果整体漂移。
     */
    val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /**
     * RGB 字节 → NCHW 归一化张量。
     *
     * @param rgb 长度须为 `width * height * 3`，通道序 **RGB**（不是帧的 BGR，调用方先转）
     * @return 形状 `[1, 3, height, width]` 展平后的 FloatArray
     *
     * 通道序与维度顺序都不能改：上游是 `(H,W,C) -> (C,H,W)` 再加批次维，
     * 弄反会让模型读到一张颜色错乱的图，却依然给出一个「看起来合理」的标签。
     */
    fun toNchw(rgb: ByteArray, width: Int, height: Int): FloatArray {
        val pixels = width * height
        require(rgb.size >= pixels * 3) {
            "RGB 数据长度 ${rgb.size} 不足 ${pixels * 3}（${width}x$height）"
        }
        val out = FloatArray(3 * pixels)
        for (c in 0 until 3) {
            val mean = MEAN[c]
            val std = STD[c]
            val planeBase = c * pixels
            for (i in 0 until pixels) {
                val v = (rgb[i * 3 + c].toInt() and 0xFF) / 255f
                out[planeBase + i] = (v - mean) / std
            }
        }
        return out
    }

    /**
     * 单标签解码：取最大 logit 的下标。
     *
     * 不做 softmax —— argmax 不受单调变换影响，上游算 softmax 只为拿置信度。
     * 省掉它结果完全一致。
     */
    fun argmax(logits: FloatArray): Int {
        var best = 0
        for (i in logits.indices) if (logits[i] > logits[best]) best = i
        return best
    }

    /** 多标签解码：sigmoid 后逐位比阈值，返回激活的下标 */
    fun activeIndices(logits: FloatArray, thresholds: FloatArray?): List<Int> {
        val active = ArrayList<Int>()
        for (i in logits.indices) {
            val p = sigmoid(logits[i])
            val t = thresholds?.getOrNull(i) ?: DEFAULT_MULTI_LABEL_THRESHOLD
            if (p > t) active += i
        }
        return active
    }

    fun sigmoid(x: Float): Float = 1f / (1f + exp(-x))

    /**
     * 上游 `training_config.json` 里没有 `best_thresholds` 时的回退值，
     * 与上游 `probabilities > 0.5` 一致。实测 v5.0.0 的 mirror_path 配置里
     * 确实没有该键，所以这就是当前生效的阈值。
     */
    const val DEFAULT_MULTI_LABEL_THRESHOLD = 0.5f
}
