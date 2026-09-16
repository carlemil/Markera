package se.kjellstrand.markera.series

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CaliberTest {

    @Test
    fun labelRoundTripsForEveryValue() {
        Caliber.entries.forEach { assertEquals(it, Caliber.fromLabel(it.label)) }
    }

    @Test
    fun unknownLabelFallsBackToNone() {
        assertEquals(Caliber.NONE, Caliber.fromLabel("9x19"))
        assertEquals(Caliber.NONE, Caliber.fromLabel(""))
        assertEquals(Caliber.NONE, Caliber.fromLabel("22LR")) // Wire values are case-sensitive.
    }

    @Test
    fun noneIsFirstSoItIsTheDefaultChoice() {
        assertEquals(Caliber.NONE, Caliber.entries.first())
    }

    /**
     * Labels are wire values the backend validates and the database stores, so they are spelled out
     * here: this list must equal `CALIBERS` in `server/src/main/kotlin/markera/server/Server.kt`
     * (ApiTest guards the same list on that side) and an existing one may never be renamed.
     */
    @Test
    fun labelsAreTheAgreedWireValues() {
        assertEquals(
            listOf(
                "-",
                "22lr", "22wmr", "17hmr",
                "32", "380", "9mm", "38", "357", "40", "10mm", "44", "45",
                "223", "243", "6.5x55", "6.5cm", "270", "308", "30-06", "7.62x39", "8x57", "9.3x62", "300wm",
            ),
            Caliber.entries.map { it.label },
        )
    }

    /** Caliber 32 is the size the user calibrated the dots against, by eye. */
    @Test
    fun caliber32DrawsAtTheCalibrationSize() {
        assertEquals(HIT_DOT_RADIUS_32_MM, Caliber.C32.hitDotRadiusMm())
        assertEquals(Caliber.C32.hitDotRadiusMm(), Caliber.NONE.hitDotRadiusMm())
    }

    @Test
    fun dotRadiusFollowsBulletDiameter() {
        assertTrue(Caliber.HMR17.hitDotRadiusMm() < Caliber.C32.hitDotRadiusMm())
        assertTrue(Caliber.C32.hitDotRadiusMm() < Caliber.C45.hitDotRadiusMm())
    }

    @Test
    fun everyCaliberHasAPositiveDiameter() {
        Caliber.entries.forEach {
            assertTrue(it.diameterMm > 0f, "${it.label} has diameter ${it.diameterMm}")
            assertTrue(it.hitDotRadiusMm() > 0f, "${it.label} would draw no dot")
        }
    }

    @Test
    fun labelsAreUnique() {
        val labels = Caliber.entries.map { it.label }
        assertEquals(labels.size, labels.toSet().size, "duplicate label in $labels")
    }
}
