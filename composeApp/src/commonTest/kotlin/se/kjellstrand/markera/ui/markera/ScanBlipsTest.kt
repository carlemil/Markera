package se.kjellstrand.markera.ui.markera

import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScanBlipsTest {

    @Test
    fun everyLapGivesFiveSpreadOutBlipsInsideTheRing() {
        // A handful of seeds: the jitter must never break the invariants.
        for (seed in 0..49) {
            val blips = scanBlips(Random(seed))
            assertEquals(5, blips.size, "seed $seed")
            blips.forEach { (angle, r) ->
                assertTrue(angle >= 0f && angle < 360f, "seed $seed angle $angle")
                assertTrue(r in 0.4f..0.9f, "seed $seed radius $r")
            }
            val angles = blips.map { it.first }
            for (i in angles.indices) {
                for (j in i + 1 until angles.size) {
                    val d = abs(angles[i] - angles[j])
                    assertTrue(min(d, 360f - d) >= 10f, "seed $seed blips $i/$j only $d° apart")
                }
            }
        }
    }
}
