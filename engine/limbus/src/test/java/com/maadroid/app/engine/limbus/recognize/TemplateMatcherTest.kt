package com.maadroid.app.engine.limbus.recognize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模板匹配的可测部分。
 *
 * OpenCV 的 native 库（libopencv_java4.so）在纯 JVM 单测里加载不了，`match` 整体
 * 只能靠仪器测试或真机验证。但两段最容易出错、又被上游语义强依赖的逻辑
 * —— 20px 去重与中心坐标换算 —— 已抽成纯函数，在这里真实执行。
 *
 * 为什么这两段值得单独钉住：
 * - 去重：TM_CCOEFF_NORMED 在目标周围会产生一片高分点，不去重会把一个按钮报成
 *   几十个匹配。上游动作代码里大量 `if len(res) > 0` 与「取 res[0]」的写法都建立在
 *   「一个目标一个结果、且第一个是分最高的」之上。
 * - 坐标换算：上游把匹配结果直接当点击点用，中心偏移或裁剪偏移算错会点到别处，
 *   而这种错误在日志里表现为「识别成功但操作无效」，极难定位。
 */
class TemplateMatcherTest {

    @Test
    fun mergeKeepsOnlyHighestScoreWithinMergeDistance() {
        // 同一目标周围的高分点簇：相差都小于 20px
        val cluster = listOf(
            Match(100, 100, 0.90),
            Match(105, 103, 0.95),
            Match(112, 108, 0.88),
        )
        val merged = TemplateMatcher.mergeNearby(cluster)
        assertEquals("同一目标应合并为 1 个", 1, merged.size)
        assertEquals("应保留分数最高的那个", 0.95, merged[0].score, 1e-9)
        assertEquals(105, merged[0].x)
    }

    @Test
    fun mergeKeepsDistinctTargetsSeparate() {
        // 相差 >= 20px 是不同目标，例如奖励列表里的多个「领取」按钮
        val hits = listOf(
            Match(100, 100, 0.90),
            Match(100, 140, 0.92),
            Match(300, 100, 0.88),
        )
        assertEquals(3, TemplateMatcher.mergeNearby(hits).size)
    }

    @Test
    fun mergeBoundaryIsExclusive() {
        // 恰好差 20px 应视为不同目标（上游用的是 < 20，不是 <=）
        assertEquals(2, TemplateMatcher.mergeNearby(
            listOf(Match(100, 100, 0.9), Match(120, 100, 0.9))
        ).size)
        // 差 19px 则合并
        assertEquals(1, TemplateMatcher.mergeNearby(
            listOf(Match(100, 100, 0.9), Match(119, 100, 0.9))
        ).size)
    }

    @Test
    fun resultIsOrderedByScoreDescending() {
        val merged = TemplateMatcher.mergeNearby(
            listOf(Match(0, 0, 0.7), Match(200, 0, 0.95), Match(400, 0, 0.85))
        )
        // 上游多处「取第一个」隐含了「第一个分最高」
        assertEquals(0.95, merged[0].score, 1e-9)
        assertTrue(merged[0].score >= merged[1].score && merged[1].score >= merged[2].score)
    }

    @Test
    fun mergeHandlesEmptyInput() {
        assertTrue(TemplateMatcher.mergeNearby(emptyList()).isEmpty())
    }

    @Test
    fun centerCoordinateAccountsForTemplateSize() {
        // 匹配点是模板左上角，返回值必须是中心
        val (x, y) = TemplateMatcher.toCenter(
            matchX = 60, matchY = 30,
            templateWidth = 24, templateHeight = 16,
            offsetX = 0, offsetY = 0, scale = 1.0,
        )
        assertEquals(72, x)
        assertEquals(38, y)
    }

    @Test
    fun centerCoordinateAddsCropOffset() {
        // 上游 mask 语义：裁剪后匹配，坐标要加回裁剪偏移，否则点击点全错
        val (x, y) = TemplateMatcher.toCenter(
            matchX = 10, matchY = 5,
            templateWidth = 20, templateHeight = 10,
            offsetX = 640, offsetY = 140, scale = 1.0,
        )
        assertEquals(640 + 20, x)
        assertEquals(140 + 10, y)
    }

    @Test
    fun centerCoordinateUndoesScreenshotScale() {
        // 缩放匹配后坐标要还原到原始尺度，否则半分辨率匹配的点击点会偏一半
        val (x, y) = TemplateMatcher.toCenter(
            matchX = 50, matchY = 25,
            templateWidth = 20, templateHeight = 10,
            offsetX = 0, offsetY = 0, scale = 0.5,
        )
        assertEquals(120, x)
        assertEquals(60, y)
    }
}
