package se.kjellstrand.markera.ui.markera

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ContinuousScanTest {

    private val size = 120

    /**
     * A target-ish scene: light paper, a dark disk and ring lines, plus sensor noise.
     * [gain] scales the whole scene (exposure, a cloud); [shadow] darkens the left
     * half by that share, fading out softly across the middle fifth.
     */
    private fun scene(
        seed: Int,
        shift: Int = 0,
        brightness: Int = 0,
        gradient: Int = 0,
        gain: Float = 1f,
        shadow: Float = 0f,
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
            val shade = 1f - shadow * ((0.6f * size - i % size) / (0.2f * size)).coerceIn(0f, 1f)
            val lit = ((v + brightness) * gain * shade).toInt()
            (lit + gradient * (i % size) / size + rnd.nextInt(-3, 4)).coerceIn(0, 255).toByte()
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
        // Moving, not "clean": a clean sample would become the reference and swallow the hole.
        assertEquals(WatchOutcome.MOVING, watch.last?.outcome)
        assertTrue(watch.offer(scene(3, paint = hole(55, 50))))
    }

    @Test
    fun aHalfPixelShiftOfSharpEdgesDoesNotFire() {
        val sharp = scene(1)
        // Half a pixel to the right: each pixel the mean of itself and its left neighbour.
        val half = LumaFrame(size, size, ByteArray(size * size) { i ->
            val left = if (i % size == 0) i else i - 1
            (((sharp.luma[i].toInt() and 0xFF) + (sharp.luma[left].toInt() and 0xFF)) / 2).toByte()
        })
        val watch = NewHoleWatch()
        val fired = watch.feed(sharp, half, half)
        assertFalse(fired.any { it })
        // Clean, not a "global change" the edges tripped.
        assertTrue(watch.last!!.reason.startsWith("no blob"), watch.last?.reason)
    }

    @Test
    fun scatteredSinglePixelNoiseIsSettledAndDoesNotFire() {
        val prev = scene(2)
        val rnd = Random(7)
        val cur = prev.luma.copyOf()
        repeat(60) {
            val i = rnd.nextInt(cur.size)
            val v = (cur[i].toInt() and 0xFF) + if (rnd.nextBoolean()) 40 else -40
            cur[i] = v.coerceIn(0, 255).toByte()
        }
        val watch = NewHoleWatch()
        val fired = watch.feed(scene(1), prev, LumaFrame(size, size, cur))
        assertFalse(fired.any { it })
        assertTrue(watch.last!!.reason.startsWith("no blob"), watch.last?.reason)
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
    fun aThreePixelShakeDoesNotFire() {
        val watch = NewHoleWatch()
        val fired = watch.feed(scene(1), scene(2, shift = 3), scene(3, shift = 3), scene(4, shift = -2))
        assertFalse(fired.any { it })
    }

    @Test
    fun aHoleStillFiresAcrossAThreePixelShake() {
        val watch = NewHoleWatch()
        val (_, _, fired) = watch.feed(
            scene(1),
            scene(2, shift = 3, paint = hole(55, 50)),
            scene(3, shift = 3, paint = hole(55, 50)),
        )
        assertTrue(fired)
    }

    @Test
    fun aSlowLightRampDoesNotFireAndAHoleAfterItStillDoes() {
        val watch = NewHoleWatch()
        // A one-sided light ramp, a few luma per sample: never one big step.
        repeat(20) { assertFalse(watch.offer(scene(it, gradient = 3 * it))) }
        assertFalse(watch.offer(scene(20, gradient = 60, paint = hole(55, 50))))
        assertTrue(watch.offer(scene(21, gradient = 60, paint = hole(55, 50))))
    }

    @Test
    fun aBrightnessChangeDoesNotFire() {
        val watch = NewHoleWatch()
        val fired = watch.feed(scene(1), scene(2, brightness = 20), scene(3, brightness = 20))
        assertFalse(fired.any { it })
    }

    // Paper at 250+ clips in the reference; 30 % less light scales every edge's
    // contrast too, which no single offset models.
    @Test
    fun aThirtyPercentGainChangeDoesNotFire() {
        val watch = NewHoleWatch()
        val fired = watch.feed(scene(1, brightness = 50), scene(2, brightness = 50, gain = 0.7f), scene(3, brightness = 50, gain = 0.7f))
        assertFalse(fired.any { it })
    }

    @Test
    fun aHoleStillFiresAcrossAThirtyPercentGainChange() {
        val watch = NewHoleWatch()
        val (_, _, fired) = watch.feed(
            scene(1, brightness = 50),
            scene(2, brightness = 50, gain = 0.7f, paint = hole(55, 50)),
            scene(3, brightness = 50, gain = 0.7f, paint = hole(55, 50)),
        )
        assertTrue(fired, watch.last?.reason)
    }

    @Test
    fun aSoftShadowOverHalfTheFrameDoesNotFire() {
        val watch = NewHoleWatch()
        val fired = watch.feed(scene(1), scene(2, shadow = 0.5f), scene(3, shadow = 0.5f))
        assertFalse(fired.any { it })
    }

    @Test
    fun aHoleInsideTheShadowStillFires() {
        val watch = NewHoleWatch()
        val (_, _, fired) = watch.feed(
            scene(1),
            scene(2, shadow = 0.5f, paint = hole(42, 60)),
            scene(3, shadow = 0.5f, paint = hole(42, 60)),
        )
        assertTrue(fired, watch.last?.reason)
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

    @Test
    fun pgmRoundTrips() {
        val frame = LumaFrame(7, 3, ByteArray(21) { (it * 12 + 3).toByte() })
        val back = decodePgm(encodePgm(frame))
        assertEquals(7, back.width)
        assertEquals(3, back.height)
        assertContentEquals(frame.luma, back.luma)
    }

    @Test
    fun aFireRecordsTheThreeComparedFrames() {
        val watch = NewHoleWatch()
        val frames = listOf(scene(1), scene(2, paint = hole(55, 50)), scene(3, paint = hole(55, 50)))
        assertTrue(watch.feed(*frames.toTypedArray()).last())
        val files = assertNotNull(watch.last?.recording())
        for ((suffix, frame) in listOf("ref.pgm", "prev.pgm", "cur.pgm").zip(frames)) {
            assertContentEquals(frame.luma, decodePgm(files.getValue(suffix)).luma)
        }
        assertTrue(files.getValue("verdict.txt").decodeToString().startsWith("rotation 0\tFIRE"))
    }
}
