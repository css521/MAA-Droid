package com.maadroid.app.engine.limbus.recognize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 分类器前后处理的验证。
 *
 * 归一化的期望值是实跑 Python 算的（`((v/255)-mean[c])/std[c]`）—— 这组常量与
 * 通道/维度顺序算错都不会报错，模型照样返回一个「看起来合理」的标签，只是错的。
 */
class ClassifierMathTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- 归一化 ----

    @Test
    fun `归一化与 Python 逐值一致`() {
        // 单像素三通道：R=0, G=128, B=64
        val rgb = byteArrayOf(0, 128.toByte(), 64)
        val out = ClassifierMath.toNchw(rgb, 1, 1)

        assertEquals(3, out.size)
        assertEquals(-2.117904f, out[0], 1e-5f)   // R 通道
        assertEquals(0.205182f, out[1], 1e-5f)    // G 通道
        assertEquals(-0.688976f, out[2], 1e-5f)   // B 通道
    }

    @Test
    fun `极值归一化正确`() {
        val out = ClassifierMath.toNchw(byteArrayOf(255.toByte(), 0, 0), 1, 1)
        assertEquals(2.248908f, out[0], 1e-5f)
    }

    @Test
    fun `输出是 NCHW 平面布局而非交错`() {
        // 两个像素：第一个纯红，第二个纯绿
        val rgb = byteArrayOf(255.toByte(), 0, 0, 0, 255.toByte(), 0)
        val out = ClassifierMath.toNchw(rgb, 2, 1)

        assertEquals(6, out.size)
        // 平面布局：[R0,R1, G0,G1, B0,B1]。若写成交错的 [R0,G0,B0, R1,G1,B1]，
        // 模型会读到一张颜色错乱的图
        assertEquals("R 平面第一个应是 255 的归一化值", 2.248908f, out[0], 1e-5f)
        assertEquals("R 平面第二个应是 0 的归一化值", -2.117904f, out[1], 1e-5f)
        assertTrue("G 平面第二个应是 255 的归一化值", out[3] > 2f)
    }

    @Test
    fun `数据不足时明确失败而不是读越界`() {
        val e = runCatching { ClassifierMath.toNchw(byteArrayOf(1, 2, 3), 2, 2) }.exceptionOrNull()
        assertTrue("应抛出可读的异常", e is IllegalArgumentException)
    }

    // ---- 单标签解码 ----

    @Test
    fun `argmax 取最大 logit`() {
        assertEquals(2, ClassifierMath.argmax(floatArrayOf(0.1f, -3f, 5.5f, 5.4f)))
        assertEquals(0, ClassifierMath.argmax(floatArrayOf(1f)))
    }

    @Test
    fun `argmax 对负值同样正确`() {
        // 不做 softmax 也不该受负值影响
        assertEquals(1, ClassifierMath.argmax(floatArrayOf(-9f, -1f, -5f)))
    }

    // ---- 多标签解码 ----

    @Test
    fun `多标签默认阈值为 0_5`() {
        // sigmoid(0) = 0.5，不大于阈值故不激活；sigmoid(1) ≈ 0.73 激活
        val active = ClassifierMath.activeIndices(floatArrayOf(1f, 0f, -1f, 3f), null)
        assertEquals(listOf(0, 3), active)
    }

    @Test
    fun `多标签逐位使用各自阈值`() {
        // 第 0 位阈值调高到 0.9 后 sigmoid(1)≈0.73 不再激活
        val active = ClassifierMath.activeIndices(
            floatArrayOf(1f, 1f),
            floatArrayOf(0.9f, 0.5f),
        )
        assertEquals(listOf(1), active)
    }

    @Test
    fun `sigmoid 基准值`() {
        assertEquals(0.5f, ClassifierMath.sigmoid(0f), 1e-6f)
        assertEquals(0.7310586f, ClassifierMath.sigmoid(1f), 1e-6f)
    }

    // ---- 标签文件解析 ----

    @Test
    fun `标签按文件里的下标就位而非行序`() {
        // 行序与下标不一致时必须按下标放，否则所有标签整体错位
        val labels = ClassifierSpec.parseLabels(
            listOf("2 third", "0 first", "1 second")
        )
        assertEquals(listOf("first", "second", "third"), labels)
    }

    @Test
    fun `下标不连续时判为不可用而不是错位`() {
        // 缺 1 号：与其给出一份错位的标签表，不如让该分类器整体不可用
        assertTrue(ClassifierSpec.parseLabels(listOf("0 a", "2 c")).isEmpty())
    }

    @Test
    fun `空行与格式错误的行被忽略`() {
        val labels = ClassifierSpec.parseLabels(listOf("0 a", "", "  ", "noindex", "1 b"))
        assertEquals(listOf("a", "b"), labels)
    }

    @Test
    fun `标签名可含空格`() {
        assertEquals(listOf("node regular encounter"), ClassifierSpec.parseLabels(listOf("0 node regular encounter")))
    }

    // ---- 模型元数据加载 ----

    private fun writeModel(
        name: String,
        config: String,
        labelFile: String,
        labels: String,
    ): File {
        val dir = File(tmp.root, "${ClassifierSpec.MODEL_ROOT}/$name")
        dir.mkdirs()
        File(dir, "best_model.onnx").writeText("fake")
        File(dir, "training_config.json").writeText(config)
        File(dir, labelFile).writeText(labels)
        return tmp.root
    }

    @Test
    fun `单标签模型从资源包读出尺寸与类别`() {
        val root = writeModel(
            "mirror_legend",
            """{"config":{"input_width":130,"input_height":110}}""",
            "classes.txt",
            "0 node_event\n1 node_empty\n",
        )
        val spec = ClassifierSpec.load(root, "mirror_legend")!!
        assertEquals(130, spec.inputWidth)
        assertEquals(110, spec.inputHeight)
        assertEquals(listOf("node_event", "node_empty"), spec.labels)
        assertTrue("有 classes.txt 应判为单标签", !spec.multiLabel)
    }

    @Test
    fun `有 connections_txt 即判为多标签`() {
        val root = writeModel(
            "mirror_path",
            """{"config":{"input_width":224,"input_height":224}}""",
            "connections.txt",
            "0 00\n1 01\n",
        )
        val spec = ClassifierSpec.load(root, "mirror_path")!!
        assertTrue(spec.multiLabel)
        assertEquals(listOf("00", "01"), spec.labels)
        // v5.0.0 的配置里没有 best_thresholds，应为 null 从而回退到 0.5
        assertEquals(null, spec.thresholds)
    }

    @Test
    fun `配置里有 best_thresholds 时按它取阈值`() {
        val root = writeModel(
            "mirror_path",
            """{"config":{"input_width":224,"input_height":224},"best_thresholds":[0.3,0.7]}""",
            "connections.txt",
            "0 00\n1 01\n",
        )
        val spec = ClassifierSpec.load(root, "mirror_path")!!
        assertEquals(2, spec.thresholds!!.size)
        assertEquals(0.3f, spec.thresholds!![0], 1e-6f)
    }

    @Test
    fun `阈值个数与标签数不符时忽略以免错位`() {
        val root = writeModel(
            "mirror_path",
            """{"config":{"input_width":224,"input_height":224},"best_thresholds":[0.3]}""",
            "connections.txt",
            "0 00\n1 01\n",
        )
        assertEquals(null, ClassifierSpec.load(root, "mirror_path")!!.thresholds)
    }

    @Test
    fun `缺文件或缺尺寸时返回 null 表示该分类器不可用`() {
        assertEquals(null, ClassifierSpec.load(tmp.root, "not_there"))

        val root = writeModel("bad", """{"config":{}}""", "classes.txt", "0 a\n")
        assertEquals("缺输入尺寸应判为不可用", null, ClassifierSpec.load(root, "bad"))
    }

    @Test
    fun `非法模型尺寸在创建原生会话前被拒绝`() {
        for (width in listOf(0, -1, Int.MAX_VALUE)) {
            val root = writeModel("bad_size", """{"config":{"input_width":$width,"input_height":80}}""",
                "classes.txt", "0 a\n")
            assertEquals(null, ClassifierSpec.load(root, "bad_size"))
        }
    }

    @Test
    fun `缺分类模型明确失败而非返回没有识别到目标`() {
        val classifier = OnnxClassifier(tmp.root)
        val error = org.junit.Assert.assertThrows(IllegalStateException::class.java) { classifier.prepare() }
        assertTrue(error.message!!.contains("mirror_legend"))
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            classifier.classify("skill_icon", listOf(ByteArray(80 * 80 * 3)))
        }
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            classifier.classifyMultiLabel("mirror_path", listOf(ByteArray(224 * 224 * 3)))
        }
        classifier.release()
    }

    /** 对着上游 clone 的真实模型目录读一遍，确认三个模型的元数据都能解出来 */
    @Test
    fun `真实模型目录的元数据与上游一致`() {
        val upstream = File("/Users/css521/project/java/LixAssistantLimbusCompany/lalc_backend")
        org.junit.Assume.assumeTrue(
            "未找到上游 clone，跳过",
            File(upstream, "ai/model/mirror_legend").isDirectory,
        )

        val legend = ClassifierSpec.load(upstream, "mirror_legend")!!
        assertEquals(130, legend.inputWidth)
        assertEquals(110, legend.inputHeight)
        assertEquals(8, legend.labels.size)
        assertTrue(!legend.multiLabel)

        val skill = ClassifierSpec.load(upstream, "skill_icon")!!
        assertEquals(80, skill.inputWidth)
        assertEquals(80, skill.inputHeight)
        assertEquals(7, skill.labels.size)

        val path = ClassifierSpec.load(upstream, "mirror_path")!!
        assertEquals(224, path.inputWidth)
        assertEquals(224, path.inputHeight)
        assertEquals(9, path.labels.size)
        assertTrue("mirror_path 是多标签", path.multiLabel)
        // 上游 v5.0.0 未给最优阈值，实际生效的是 0.5
        assertEquals(null, path.thresholds)
        assertEquals("00", path.labels.first())
    }
}
