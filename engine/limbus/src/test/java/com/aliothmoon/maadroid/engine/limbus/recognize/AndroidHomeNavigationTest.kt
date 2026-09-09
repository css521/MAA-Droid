package com.aliothmoon.maadroid.engine.limbus.recognize

import org.junit.Assert.*
import org.junit.Test

class AndroidHomeNavigationTest {
    private val drive = Match(980, 651, .95)
    private val label = TextMatch("Drive", 982, 690, .96)

    @Test fun englishLabelMustBelongToTheDetectedDriveIcon() {
        assertNotNull(AndroidHomeNavigation.confirmedDriveLabel(drive, listOf(label), "en", .85))
        for (invalid in listOf(label.copy(x = 820), label.copy(y = 590), label.copy(score = .6),
            label.copy(text = "Drive settings"))) {
            assertNull(AndroidHomeNavigation.confirmedDriveLabel(drive, listOf(invalid), "en", .85))
        }
    }

    @Test fun englishHomeDoesNotSilentlyPassTheChineseLanguageGate() {
        assertNull(AndroidHomeNavigation.confirmedDriveLabel(drive, listOf(label), "zh", .85))
        assertNotNull(AndroidHomeNavigation.confirmedDriveLabel(drive, listOf(label.copy(text = "驾驶舱")), "zh", .85))
        assertNull(AndroidHomeNavigation.confirmedDriveLabel(drive, listOf(label), "ja", .85))
    }

    @Test fun roundDRequiresAnIndependentEnglishNeighborAndStrongDriveIcon() {
        val ambiguous = label.copy(text = "Orive")
        val neighbor = TextMatch("Sinners", 902, 690, .83)
        assertNotNull(AndroidHomeNavigation.confirmedDriveLabel(drive, listOf(ambiguous, neighbor), "en", .85))
        assertNull(AndroidHomeNavigation.confirmedDriveLabel(drive, listOf(ambiguous), "en", .85))
        assertNull(AndroidHomeNavigation.confirmedDriveLabel(drive.copy(score = .87), listOf(ambiguous, neighbor), "en", .85))
        assertNull(AndroidHomeNavigation.confirmedDriveLabel(drive, listOf(ambiguous, neighbor.copy(y = 580)), "en", .85))
        assertNull(AndroidHomeNavigation.confirmedDriveLabel(drive, listOf(ambiguous, neighbor), "zh", .85))
    }

    @Test fun windowMustBeOnTheSameNavigationRowAndLeftOfDrive() {
        assertTrue(AndroidHomeNavigation.isWindowBesideDrive(Match(825, 650, .9), drive))
        assertFalse(AndroidHomeNavigation.isWindowBesideDrive(Match(980, 650, .9), drive))
        assertFalse(AndroidHomeNavigation.isWindowBesideDrive(Match(825, 400, .9), drive))
        assertFalse(AndroidHomeNavigation.isWindowBesideDrive(Match(1120, 650, .9), drive))
    }

    @Test fun ordinaryGameplayAndTitleTemplatesKeepTheirExistingMatchingPolicy() {
        assertTrue(AndroidHomeNavigation.supports("main_drive_no_text"))
        assertTrue(AndroidHomeNavigation.supports("main_window_no_text"))
        assertTrue(AndroidHomeNavigation.supports("main_drive_with_text"))
        for (name in listOf("inferno", "win_rate", "clear_all_caches", "charge_enkephalin")) {
            assertFalse(AndroidHomeNavigation.supports(name))
        }
    }

    @Test fun tinyDriveLabelCanBeConfirmedByIndependentNavigationWords() {
        val labels = listOf(
            TextMatch("Drlre", 982, 690, .76),
            TextMatch("Sinners", 903, 690, .867),
            TextMatch("Extrect", 1140, 690, .869),
        )
        assertNotNull(AndroidHomeNavigation.confirmedDriveLabel(drive, labels, "en", .85))
        assertEquals(GameLanguageObservation.Confirmed, AndroidHomeNavigation.languageObservation(drive, labels, "en"))
        assertEquals(GameLanguageObservation.Mismatch("zh", "en"), AndroidHomeNavigation.languageObservation(drive, labels, "zh"))
    }

    @Test fun missingUnreadableOrConflictingWordsAreNotAWrongLanguage() {
        val sets = listOf(
            emptyList(),
            listOf(label.copy(text = "Drlre", score = .76)),
            listOf(label, label.copy(text = "驾驶舱")),
            listOf(TextMatch("Sinners", 903, 690, .9), TextMatch("Extract", 1140, 350, .9)),
            listOf(TextMatch("Sinnars", 903, 690, .9), TextMatch("Extrect", 1140, 690, .9)),
        )
        sets.forEach { labels ->
            assertEquals(GameLanguageObservation.Uncertain, AndroidHomeNavigation.languageObservation(drive, labels, "en"))
        }
    }

    @Test fun oneButtonCannotSupplyBothIndependentLabels() {
        val labels = listOf(TextMatch("Sinners", 903, 690, .9), TextMatch("Extract", 904, 690, .9))
        assertEquals(GameLanguageObservation.Uncertain, AndroidHomeNavigation.languageObservation(drive, labels, "en"))
    }
}
