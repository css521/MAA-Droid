package com.maadroid.app.engine.limbus.recognize

import org.junit.Assert.*
import org.junit.Test

class BattlePerceptionTest {
    @Test fun offsetsKeepScreenshotScaleAndBothSkillRows() {
        val anchors = listOf(
            BattleSkillAnchor(100, 560, .9, 1.199), BattleSkillAnchor(200, 570, .9, 1.2),
            BattleSkillAnchor(300, 590, .9, .8), BattleSkillAnchor(400, 600, .9, 1.3),
            BattleSkillAnchor(500, 580, .9, 1.0), BattleSkillAnchor(600, 559, .9, 1.0),
            BattleSkillAnchor(700, 601, .9, 1.0), BattleSkillAnchor(900, 560, .9, 1.0),
        )
        assertEquals(listOf(Crop(75, 500, 80, 80), Crop(180, 515, 80, 80), Crop(275, 530, 80, 80), Crop(380, 545, 80, 80)),
            BattlePerception.skillRegions(anchors, 800))
    }

    @Test fun duplicateAnchorsKeepBestScaleAndDoNotMergeTwentyPixelBoundary() {
        val best = BattleSkillAnchor(101, 560, .95, 1.25)
        assertEquals(listOf(best, BattleSkillAnchor(121, 560, .9, 1.0)), BattlePerception.mergeAnchors(listOf(
            BattleSkillAnchor(100, 560, .85, .8), best, BattleSkillAnchor(121, 560, .9, 1.0),
        )))
    }

    @Test fun classifierLengthMismatchCannotShiftLabelsOntoWrongCoordinates() {
        assertTrue(BattlePerception.bindSkills(listOf(Crop(0, 0, 80, 80)), emptyList()).isEmpty())
        assertEquals(listOf(BattleSkillIcon("neutral", 50, 60)),
            BattlePerception.bindSkills(listOf(Crop(10, 20, 80, 80)), listOf("neutral")))
    }

    @Test fun ownerIntervalsIncludeLeftBoundaryAndDoNotBorrowUnavailableEgo() {
        val a = BattleSinnerAvatar(100, 690, listOf(110))
        val b = BattleSinnerAvatar(200, 690, listOf(109))
        val c = BattleSinnerAvatar(300, 690, listOf(130))
        val skills = listOf(99, 100, 199, 200, 299, 300, 900).map { BattleSkillIcon("neutral", it, 550) }
        assertEquals(listOf(a, c), BattlePerception.threatenedAvatars(skills, listOf(c, a, b)))
    }

    @Test fun corrosionMarkerCannotAuthorizeNeighboringCardOrWrongRow() {
        val left = Match(400, 150, .9)
        val right = Match(550, 150, .9)
        assertEquals(listOf(left), BattlePerception.safeEgoDetails(BattleEgoPanel(
            listOf(right, left), listOf(Match(390, 160, .9), Match(500, 205, .9)), true,
        )))
    }

    @Test fun unknownPageIsNotClosedBattlePanel() {
        assertFalse(BattleEgoPanel(emptyList(), emptyList(), false).closed)
        assertFalse(BattlePerception.allUnselected(emptyList()))
        assertFalse(BattleSinnerAvatar(0, 0, emptyList()).egoAvailable)
    }

    @Test fun avatarCropAndHpColorRespectUpstreamGeometryAndBgrOrder() {
        assertEquals(Crop(79, 625, 80, 55), BattlePerception.avatarCrop(100, 690))
        assertTrue(BattlePerception.isHpPixel(20, 60, 205))
        assertTrue(BattlePerception.isHpPixel(60, 100, 245))
        assertFalse(BattlePerception.isHpPixel(40, 80, 246))
        assertFalse(BattlePerception.isHpPixel(225, 80, 40))
    }

    @Test fun avatarGrayKeepsUpstreamEnhanceChannelSwap() {
        assertArrayEquals(intArrayOf(29, 76, 150), BattlePerception.avatarGray(byteArrayOf(
            0, 0, 255.toByte(), 255.toByte(), 0, 0, 0, 255.toByte(), 0,
        )))
    }

    @Test fun dilationUsesEightNeighborsWithoutWrappingRows() {
        assertArrayEquals(intArrayOf(0, 200, 200, 0, 200, 200),
            BattlePerception.dilateGray(intArrayOf(0, 0, 200, 0, 0, 0), 3, 2))
    }

    @Test fun clustersUseDiagonalConnectivityStrictThresholdAndTruncatedMean() {
        assertEquals(listOf(110, 200), BattlePerception.clusterBrightness(intArrayOf(
            109, 0, 0, 200,
            0, 112, 0, 5,
            0, 0, 0, 0,
        ), 4, 3))
        assertTrue(BattlePerception.clusterBrightness(IntArray(6) { 5 }, 3, 2).isEmpty())
    }

    @Test fun avatarScoresUseDilatedClusterMeansRatherThanBrightestPixel() {
        val bgr = ByteArray(80 * 55 * 3)
        fun pixel(x: Int, y: Int, value: Int) { repeat(3) { bgr[(y * 80 + x) * 3 + it] = value.toByte() } }
        pixel(5, 5, 109)
        pixel(50, 40, 110)
        assertEquals(listOf(109, 110), BattlePerception.avatarScores(bgr))
        assertTrue(BattleSinnerAvatar(100, 690, BattlePerception.avatarScores(bgr)).egoAvailable)
        assertTrue(BattlePerception.avatarScores(ByteArray(bgr.size)).isEmpty())
    }
}
