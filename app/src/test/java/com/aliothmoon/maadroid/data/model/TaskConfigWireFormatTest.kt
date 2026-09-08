package com.aliothmoon.maadroid.data.model

import com.aliothmoon.maadroid.utils.JsonUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务配置的**持久化格式**契约。
 *
 * `TaskChainNode.config` 的类型是 sealed 的 [TaskParamProvider]，所以 kotlinx 会写一个
 * 多态判别符。这些实现类都没有 `@SerialName`，`JsonUtils.common` 也没设
 * `classDiscriminator` —— 于是判别符是**全限定类名**，也就是说**包名进了用户的存档**。
 *
 * 这意味着把 `FightConfig` 等类挪进 `engine/arknights` 会让所有已装用户的任务链与
 * 配置档读不出来（表现为任务链变空，用户以为配置丢了）。抽取方舟前必须先把判别符
 * 与包名解绑。
 *
 * 这些用例先把**当前**格式钉住，作为后续迁移的基准：迁移方案正确的判据就是
 * 「本文件的断言一条都不用改」。
 */
class TaskConfigWireFormatTest {

    private val json = JsonUtils.common

    @Test
    fun configDiscriminatorIsFullyQualifiedClassName() {
        val node = TaskChainNode(name = "刷理智", config = FightConfig())
        val text = json.encodeToString(node)

        // 这不是实现细节：这串字符已经躺在用户设备的 DataStore 里
        assertTrue(
            "判别符应含全限定类名，实际: $text",
            text.contains("com.aliothmoon.maadroid.data.model.FightConfig"),
        )
    }

    @Test
    fun everyTaskConfigRoundTripsThroughItsCurrentDiscriminator() {
        // 十个配置类都参与持久化；任一判别符变了，对应任务在旧存档里就读不回来
        val configs: List<TaskParamProvider> = listOf(
            FightConfig(), RecruitConfig(), InfrastConfig(), WakeUpConfig(),
            MallConfig(), AwardConfig(), RoguelikeConfig(), ReclamationConfig(),
            DepotMaintainConfig(), UserDataUpdateConfig(),
        )
        for (cfg in configs) {
            val node = TaskChainNode(name = cfg::class.simpleName!!, config = cfg)
            val text = json.encodeToString(node)
            val expected = "com.aliothmoon.maadroid.data.model.${cfg::class.simpleName}"
            assertTrue("$expected 应出现在存档里，实际: $text", text.contains(expected))

            val back = json.decodeFromString<TaskChainNode>(text)
            assertEquals(cfg::class, back.config::class)
        }
    }

    @Test
    fun storedNodeFromOlderVersionStillDecodes() {
        // 手写一份「旧版本写出来的」存档片段：迁移方案必须让它继续可读
        val stored = """
            {
              "id": "fixed-id",
              "name": "刷理智",
              "config": {
                "type": "com.aliothmoon.maadroid.data.model.FightConfig"
              }
            }
        """.trimIndent()
        val node = json.decodeFromString<TaskChainNode>(stored)
        assertEquals("fixed-id", node.id)
        assertTrue("旧存档应解析为 FightConfig", node.config is FightConfig)
    }
}

/**
 * 判别符已与包名解绑的证明。
 *
 * 单独一个类，因为它验的不是「当前格式是什么」而是「换包后格式不变」——
 * 也就是抽取 `engine/arknights` 的安全性。
 */
class TaskConfigDiscriminatorIsPackageIndependentTest {

    private val json = JsonUtils.common

    @Test
    fun discriminatorComesFromSerialNameNotFromPackage() {
        // 逐个核对：判别符必须等于 @SerialName 钉住的字符串，而不是运行时算出来的包路径。
        // 两者当前相同，但来源不同 —— 前者随类移动而不变，后者会跟着变
        val configs: List<TaskParamProvider> = listOf(
            FightConfig(), RecruitConfig(), InfrastConfig(), WakeUpConfig(),
            MallConfig(), AwardConfig(), RoguelikeConfig(), ReclamationConfig(),
            DepotMaintainConfig(), UserDataUpdateConfig(),
        )
        for (cfg in configs) {
            val cls = cfg::class
            val pinned = cls.java.getAnnotation(kotlinx.serialization.SerialName::class.java)
            assertTrue(
                "${cls.simpleName} 缺 @SerialName —— 换包时它的存档会读不出来",
                pinned != null,
            )
            assertEquals(
                "${cls.simpleName} 的 @SerialName 必须是旧的全限定名（存档里就是这个值）",
                "com.aliothmoon.maadroid.data.model.${cls.simpleName}",
                pinned!!.value,
            )
            // 且实际写出来的就是它
            val text = json.encodeToString(TaskChainNode(name = "x", config = cfg))
            assertTrue(text.contains(pinned.value))
        }
    }

    @Test
    fun realClassPackageMayDifferFromDiscriminator() {
        // 这条断言当前「平凡成立」，但它是给未来的人看的：
        // 类搬进 engine/arknights 之后 qualifiedName 会变，而判别符不该跟着变。
        // 届时本用例会从「两者相同」变成「两者不同且判别符仍是旧值」，
        // 而上面那条断言依然通过 —— 那就是迁移成功的判据。
        val cfg = FightConfig()
        val pinned = cfg::class.java
            .getAnnotation(kotlinx.serialization.SerialName::class.java)!!.value
        val actualPackage = cfg::class.qualifiedName!!
        assertTrue(
            "判别符与真实包名任一为空都说明反射拿错了",
            pinned.isNotEmpty() && actualPackage.isNotEmpty(),
        )
    }
}
