package se.kjellstrand.markera.series

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaliberTest {

    @Test
    fun labelRoundTripsForEveryValue() {
        Caliber.BUILT_IN.forEach { assertEquals(it, Caliber.fromLabel(it.label)) }
    }

    /** A label no list knows (a removed or another device's custom one) is kept, drawn at [Caliber.NONE]'s size. */
    @Test
    fun unknownLabelKeepsItsLabelAtTheCalibrationSize() {
        listOf("9x19", "22LR").forEach { label -> // Wire values are case-sensitive.
            val caliber = Caliber.fromLabel(label)
            assertEquals(label, caliber.label)
            assertNotEquals(Caliber.NONE, caliber)
            assertEquals(Caliber.NONE.diameterMm, caliber.diameterMm)
        }
        assertEquals(Caliber.NONE, Caliber.fromLabel(""))
    }

    @Test
    fun customLabelResolvesToItsDiameterButNeverShadowsABuiltIn() {
        val custom = listOf(Caliber("38wc", 9.07f), Caliber("9mm", 1f))
        assertEquals(9.07f, Caliber.fromLabel("38wc", custom).diameterMm)
        assertEquals(Caliber.MM9.diameterMm, Caliber.fromLabel("9mm", custom).diameterMm)
    }

    @Test
    fun equalityIsByLabel() {
        assertEquals(Caliber("38wc", 9.07f), Caliber("38wc", 7.92f))
        assertEquals(Caliber("38wc", 9.07f).hashCode(), Caliber("38wc", 7.92f).hashCode())
    }

    @Test
    fun labelValidationFollowsTheServerShape() {
        listOf("38wc", "a", "7.62x39", "30-06", "45 ACP", "a,b/c", "x".repeat(16)).forEach {
            assertTrue(isValidCaliberLabel(it), it)
        }
        listOf("", " ", "x".repeat(17), "38wc;", "a=b", "kulä", "38	wc").forEach {
            assertFalse(isValidCaliberLabel(it), it)
        }
    }

    @Test
    fun diameterAcceptsEitherDecimalSeparatorWithinRange() {
        assertEquals(9.07f, parseCaliberDiameter("9.07"))
        assertEquals(9.07f, parseCaliberDiameter(" 9,07 "))
        assertEquals(1f, parseCaliberDiameter("1"))
        assertEquals(20f, parseCaliberDiameter("20"))
        listOf("", "abc", "0.99", "20.5", "-5", "9..0").forEach { assertNull(parseCaliberDiameter(it), it) }
    }

    @Test
    fun customCalibersRoundTripThroughTheStoreEncoding() {
        val list = listOf(Caliber("38wc", 9.07f), Caliber("45 a,b/c", 11.45f))
        val back = decodeCustomCalibers(list.encodeCustomCalibers())
        assertEquals(list, back)
        assertEquals(list.map { it.diameterMm }, back.map { it.diameterMm })
        assertEquals(emptyList(), decodeCustomCalibers(null))
        assertEquals(emptyList(), decodeCustomCalibers(""))
        assertEquals(listOf(Caliber("ok", 5f)), decodeCustomCalibers("broken;ok=5;=3;x=y"))
    }

    @Test
    fun customCalibersSortAfterEveryBuiltIn() {
        assertTrue(Caliber("38wc", 9.07f).ordinal > Caliber.WM300.ordinal)
        assertEquals(0, Caliber.NONE.ordinal)
    }

    @Test
    fun noneIsFirstSoItIsTheDefaultChoice() {
        assertEquals(Caliber.NONE, Caliber.BUILT_IN.first())
    }

    /**
     * Labels are wire values the database stores, so they are spelled out here: an existing one may
     * never be renamed. The server checks only their shape (`CALIBER_SHAPE` in
     * `server/src/main/kotlin/markera/server/Server.kt`; ApiTest posts this same list).
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
            Caliber.BUILT_IN.map { it.label },
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
        Caliber.BUILT_IN.forEach {
            assertTrue(it.diameterMm > 0f, "${it.label} has diameter ${it.diameterMm}")
            assertTrue(it.hitDotRadiusMm() > 0f, "${it.label} would draw no dot")
        }
    }

    @Test
    fun labelsAreUnique() {
        val labels = Caliber.BUILT_IN.map { it.label }
        assertEquals(labels.size, labels.toSet().size, "duplicate label in $labels")
    }
}
