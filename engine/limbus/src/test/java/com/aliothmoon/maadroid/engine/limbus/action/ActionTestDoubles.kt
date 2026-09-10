package com.aliothmoon.maadroid.engine.limbus.action

import com.aliothmoon.maadroid.engine.InputSink
import com.aliothmoon.maadroid.engine.limbus.pipeline.PipelineNode
import com.aliothmoon.maadroid.engine.limbus.recognize.Crop
import com.aliothmoon.maadroid.engine.limbus.recognize.Match
import com.aliothmoon.maadroid.engine.limbus.recognize.Recognizer
import com.aliothmoon.maadroid.engine.limbus.recognize.TemplateIndex
import com.aliothmoon.maadroid.engine.limbus.recognize.TextMatch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 动作层的测试替身。
 *
 * 存在的意义就是让 35 个动作能在纯 JVM 里验证 —— 它们全都只经 [ActionContext]
 * 与外界打交道，所以喂假的识别器与输入即可跑完整条逻辑，不需要真机、不需要 OpenCV。
 */

/** 记录所有注入事件，供断言点击/按键的坐标与顺序 */
class FakeInput : InputSink {
    val events = mutableListOf<String>()

    override fun touchDown(x: Int, y: Int, contact: Int) {
        events += "down($x,$y)"
    }

    override fun touchMove(x: Int, y: Int, contact: Int) {
        events += "move($x,$y)"
    }

    override fun touchUp(x: Int, y: Int, contact: Int) {
        events += "up($x,$y)"
    }

    override fun touchCancel() {
        events += "cancel"
    }

    override fun keyDown(keyCode: Int) {
        events += "keyDown($keyCode)"
    }

    override fun keyUp(keyCode: Int) {
        events += "keyUp($keyCode)"
    }

    /** 点击 = down 紧跟同坐标 up，便于断言「点了哪些位置」 */
    fun clicks(): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        val re = Regex("""(down|up)\((\d+),(\d+)\)""")
        var pending: Pair<Int, Int>? = null
        for (e in events) {
            val m = re.matchEntire(e) ?: continue
            val p = m.groupValues[2].toInt() to m.groupValues[3].toInt()
            if (m.groupValues[1] == "down") pending = p
            else if (pending == p) {
                result += p
                pending = null
            } else pending = null
        }
        return result
    }

    fun keyPresses(): List<Int> = events.mapNotNull {
        Regex("""keyDown\((\d+)\)""").matchEntire(it)?.groupValues?.get(1)?.toInt()
    }
}

/**
 * 可编程识别器。
 *
 * [templateHits] 按模板名给出固定结果；[textHits] 给 OCR 结果。
 * [templateQueue] 可为同一模板名安排**逐次不同**的返回，用来测「等待某物消失」这类
 * 需要状态变化才能退出的循环 —— 否则那些 while 会在测试里跑成死循环。
 */
class FakeRecognizer : Recognizer {
    val templateHits = mutableMapOf<String, List<Match>>()
    val templateQueue = mutableMapOf<String, MutableList<List<Match>>>()
    var textHits: List<TextMatch> = emptyList()
    var classifyResult: List<String> = emptyList()
    val templateCalls = mutableListOf<String>()

    fun onTemplate(name: String, vararg matches: Match) {
        templateHits[name] = matches.toList()
    }

    fun onTemplateSequence(name: String, vararg rounds: List<Match>) {
        templateQueue[name] = rounds.toMutableList()
    }

    override suspend fun templateMatch(
        template: String,
        threshold: Double,
        crop: Crop?,
        maskTemplate: Crop?,
        screenshotScale: Double,
        onMiss: ((Double, Int, Int) -> Unit)?,
    ): List<Match> {
        templateCalls += template
        templateQueue[template]?.let { q ->
            if (q.isNotEmpty()) return q.removeAt(0)
        }
        return templateHits[template] ?: emptyList()
    }

    override suspend fun detectText(crop: Crop?, threshold: Double): List<TextMatch> = textHits

    /** 记录每次 findText 的目标串，便于断言"用的是文字判据而不是模板" */
    val textCalls = mutableListOf<String>()

    override suspend fun findText(target: String, crop: Crop?, threshold: Double): List<TextMatch> {
        textCalls += target
        return textHits.filter { target in it.text }
    }

    override suspend fun classify(model: String, regions: List<Crop>): List<String> = classifyResult

    /** 多标签结果，键是模型名；未设置则返回空表（等价于分类器不可用） */
    val multiLabelResult = mutableMapOf<String, List<List<String>>>()

    override suspend fun classifyMultiLabel(model: String, regions: List<Crop>): List<List<String>> =
        multiLabelResult[model] ?: emptyList()

    override suspend fun colorTemplateMatch(template: String, threshold: Double, crop: Crop?) =
        templateMatch(template, threshold, crop)

    override suspend fun featureMatch(template: String, threshold: Double, crop: Crop?) =
        templateMatch(template, threshold, crop)

    override suspend fun pyramidTemplateMatch(template: String, threshold: Double, crop: Crop?) =
        templateMatch(template, threshold, crop)

    override suspend fun preciseTemplateMatch(template: String, threshold: Double, crop: Crop?) =
        templateMatch(template, threshold, crop)
}

/** 扁平 + 分组两种配置的内存实现 */
class FakeConfig : LimbusConfig {
    val flat = mutableMapOf<String, Any>()
    val groups = mutableMapOf<String, List<Any>>()

    private fun key(section: String, k: String) = "$section.$k"

    fun put(section: String, k: String, v: Any) = apply { flat[key(section, k)] = v }
    fun putGroups(section: String, k: String, v: List<Any>) = apply { groups[key(section, k)] = v }

    override fun int(section: String, key: String, default: Int) =
        (flat[key(section, key)] as? Int) ?: default

    override fun bool(section: String, key: String, default: Boolean) =
        (flat[key(section, key)] as? Boolean) ?: default

    override fun str(section: String, key: String, default: String) =
        (flat[key(section, key)] as? String) ?: default

    @Suppress("UNCHECKED_CAST")
    override fun list(section: String, key: String) =
        (flat[key(section, key)] as? List<String>) ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    override fun listAt(section: String, key: String, index: Int) =
        (groups[key(section, key)]?.getOrNull(index) as? List<String>) ?: emptyList()

    override fun intAt(section: String, key: String, index: Int, default: Int) =
        (groups[key(section, key)]?.getOrNull(index) as? Int) ?: default

    override fun boolAt(section: String, key: String, index: Int, default: Boolean) =
        (groups[key(section, key)]?.getOrNull(index) as? Boolean) ?: default

    override fun strAt(section: String, key: String, index: Int, default: String) =
        (groups[key(section, key)]?.getOrNull(index) as? String) ?: default

    override fun groupCount(section: String, key: String) =
        groups[key(section, key)]?.size ?: 0

    /** 形状不规则的键（如 mirror_replace_skill 的罪人名→技能顺序字典）直接塞 JSON */
    override fun rawAt(section: String, key: String, index: Int): JsonElement? =
        groups[key(section, key)]?.getOrNull(index) as? JsonElement

    /** 便捷：按 JSON 字面量放一组不规则配置 */
    fun putRawGroups(section: String, k: String, vararg json: String) = apply {
        groups[key(section, k)] = json.map { Json.parseToJsonElement(it) }
    }
}

class FakeTemplateIndex(private val byTag: Map<String, List<String>> = emptyMap()) : TemplateIndex {
    override fun namesByTag(tag: String) = byTag[tag] ?: emptyList()
    override fun contains(name: String) = byTag.values.any { name in it }
}

/** 测试用上下文。delay 不真睡，让带 while 的动作瞬间跑完 */
class TestActionContext(
    override val node: PipelineNode = PipelineNode(),
    override val nodeName: String = "test_node",
    override val input: InputSink = FakeInput(),
    override val recognize: Recognizer = FakeRecognizer(),
    override val config: LimbusConfig = FakeConfig(),
    override val templates: TemplateIndex = FakeTemplateIndex(),
    override val recognizeResult: List<Match> = emptyList(),
) : ActionContext {

    /** 便捷访问：绝大多数用例用的就是默认的记录型替身 */
    val fakeInput: FakeInput get() = input as FakeInput
    val fakeRecognizer: FakeRecognizer get() = recognize as FakeRecognizer
    val fakeConfig: FakeConfig get() = config as FakeConfig

    val logs = mutableListOf<String>()
    val slept = mutableListOf<Double>()
    private val counters = mutableMapOf<String, Int>()
    var cancelled = false

    override fun log(message: String) {
        logs += message
    }

    override fun ensureActive() {
        if (cancelled) throw IllegalStateException("已取消")
    }

    override suspend fun delay(seconds: Double) {
        slept += seconds
    }

    override fun counterOf(nodeName: String) = counters[nodeName] ?: 0

    override fun incrementCounter(nodeName: String): Int {
        val next = counterOf(nodeName) + 1
        counters[nodeName] = next
        return next
    }

    fun setCounter(name: String, value: Int) {
        counters[name] = value
    }
}

/** 从 JSON 字面量造节点参数，跟资源包里的真实形状一致 */
fun paramsOf(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

fun nodeWith(paramsJson: String, action: String = "empty"): PipelineNode =
    PipelineNode(action = action, params = paramsOf(paramsJson))
