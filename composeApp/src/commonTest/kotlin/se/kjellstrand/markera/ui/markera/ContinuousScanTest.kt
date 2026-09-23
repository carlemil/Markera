package se.kjellstrand.markera.ui.markera

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContinuousScanTest {

    private val size = 120

    /** A target-ish scene: light paper, a dark disk and ring lines, plus sensor noise. */
    private fun scene(
        seed: Int,
        shift: Int = 0,
        brightness: Int = 0,
        paint: (x: Int, y: Int) -> Int? = { _, _ -> null },
    ): LumaFrame {
        val rnd = Random(seed)
        val luma = ByteArray(size * size) { i ->
            val x = i % size - shift
            val y = i / size
            val dx = x - 60
            val dy = y - 60
            val r2 = dx * dx + dy * dy
            val base = when {
                r2 < 30 * 30 -> 40
                r2 in 44 * 44..45 * 45 -> 60
                else -> 200
            }
            val v = paint(i % size, y) ?: base
            (v + brightness + rnd.nextInt(-3, 4)).coerceIn(0, 255).toByte()
        }
        return LumaFrame(size, size, luma)
    }

    private fun hole(cx: Int, cy: Int, r: Int = 3, luma: Int = 230): (Int, Int) -> Int? = { x, y ->
        if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) luma else null
    }

    private fun NewHoleWatch.feed(vararg frames: LumaFrame) = frames.map { offer(it) }

    @Test
    fun noiseAloneNeverFires() {
        val watch = NewHoleWatch()
        repeat(6) { assertFalse(watch.offer(scene(seed = it))) }
    }

    @Test
    fun aNewHoleFiresOnceItHasHeldForTwoSamples() {
        val watch = NewHoleWatch()
        watch.offer(scene(1))
        // Appears: it moved since the previous sample, so not yet.
        assertFalse(watch.offer(scene(2, paint = hole(55, 50))))
        assertTrue(watch.offer(scene(3, paint = hole(55, 50))))
    }

    @Test
    fun aHoleOnWhitePaperFiresToo() {
        val watch = NewHoleWatch()
        val (_, _, fired) = watch.feed(
            scene(1),
            scene(2, paint = hole(100, 100, luma = 30)),
            scene(3, paint = hole(100, 100, luma = 30)),
        )
        assertTrue(fired)
    }

    @Test
    fun aOneSampleFlickerDoesNotFire() {
        val watch = NewHoleWatch()
        val fired = watch.feed(scene(1), scene(2, paint = hole(55, 50)), scene(3), scene(4))
        assertFalse(fired.any { it })
    }

    @Test
    fun aOnePixelCameraCreepDoesNotFire() {
        val watch = NewHoleWatch()
        val fired = watch.feed(scene(1), scene(2, shift = 1), scene(3, shift = 1))
        assertFalse(fired.any { it })
    }

    @Test
    fun aBrightnessChangeDoesNotFire() {
        val watch = NewHoleWatch()
        val fired = watch.feed(scene(1), scene(2, brightness = 20), scene(3, brightness = 20))
        assertFalse(fired.any { it })
    }

    @Test
    fun aHandSizedBlobDoesNotFire() {
        val watch = NewHoleWatch()
        val hand = hole(90, 30, r = 15, luma = 120)
        val fired = watch.feed(scene(1), scene(2, paint = hand), scene(3, paint = hand))
        assertFalse(fired.any { it })
    }

    @Test
    fun aThinStreakDoesNotFire() {
        val watch = NewHoleWatch()
        val streak: (Int, Int) -> Int? = { x, y -> if (y == 100 && x in 20..60) 30 else null }
        val fired = watch.feed(scene(1), scene(2, paint = streak), scene(3, paint = streak))
        assertFalse(fired.any { it })
    }

    @Test
    fun resetMakesTheScannedSceneTheNewReference() {
        val watch = NewHoleWatch()
        watch.feed(scene(1), scene(2, paint = hole(55, 50)))
        assertTrue(watch.offer(scene(3, paint = hole(55, 50))))
        watch.reset()
        repeat(3) { assertFalse(watch.offer(scene(4 + it, paint = hole(55, 50)))) }
    }
}
