package se.kjellstrand.markera.ui.markera

import kotlin.math.abs
import kotlin.math.max
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

    // A re-meter offers nothing while it runs, so the first sample after it
    // compares an exposure step against the last one before it.
    @Test
    fun anExposureStepAcrossAReMeterNeitherFiresNorSticksInMoving() {
        val watch = NewHoleWatch()
        watch.feed(scene(1, brightness = 20), scene(2, brightness = 20))
        for (seed in 3..5) {
            assertFalse(watch.offer(scene(seed, brightness = 30, gain = 1.15f)), watch.last?.reason)
            assertTrue(watch.last?.outcome != WatchOutcome.MOVING, watch.last?.reason)
        }
    }

    @Test
    fun aHoleShotDuringAReMeterFiresAfterIt() {
        val watch = NewHoleWatch()
        watch.feed(scene(1, brightness = 20), scene(2, brightness = 20))
        val fired = watch.feed(
            scene(3, brightness = 30, gain = 1.15f, paint = hole(55, 50)),
            scene(4, brightness = 30, gain = 1.15f, paint = hole(55, 50)),
        )
        assertTrue(fired.any { it }, watch.last?.reason)
    }

    @Test
    fun aReMeterIsDueOnlyAfterTheIntervalAndWhenSettled() {
        fun verdict(outcome: WatchOutcome) = WatchVerdict("", 1, 1, 0, emptyList(), outcome)
        assertFalse(remeterDue(REMETER_INTERVAL_MS - 1, verdict(WatchOutcome.OTHER)))
        assertTrue(remeterDue(REMETER_INTERVAL_MS, verdict(WatchOutcome.OTHER)))
        assertTrue(remeterDue(REMETER_INTERVAL_MS, null))
        assertFalse(remeterDue(REMETER_INTERVAL_MS, verdict(WatchOutcome.MOVING)))
        assertFalse(remeterDue(REMETER_INTERVAL_MS, verdict(WatchOutcome.FIRE)))
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

    /**
     * A [big]² frame at the real watch grid: grid lines every 50 px over light
     * paper plus an off-centre black disk with light rings, anti-aliased, turned
     * [degrees] about the frame centre, plus sensor noise. At 600 px a 0.5° turn
     * moves the corners ~2.6 px each way while the centre stays: no one shift fits.
     */
    private val big = 600

    private fun turned(seed: Int, degrees: Double, paint: (x: Int, y: Int) -> Int? = { _, _ -> null }): LumaFrame {
        val rnd = Random(seed)
        val a = degrees * kotlin.math.PI / 180
        val cos = kotlin.math.cos(a)
        val sin = kotlin.math.sin(a)
        fun cover(dist: Double) = (2.0 - dist).coerceIn(0.0, 1.0) // a ~3 px line, 1 px ramps
        val luma = ByteArray(big * big) { i ->
            val px = i % big
            val py = i / big
            val cx = px - big / 2.0
            val cy = py - big / 2.0
            val u = cos * cx - sin * cy + big / 2.0
            val v = sin * cx + cos * cy + big / 2.0
            val gu = abs((u + 25).mod(50.0) - 25)
            val gv = abs((v + 25).mod(50.0) - 25)
            val paper = 200 - 140 * max(cover(gu), cover(gv))
            val d = kotlin.math.hypot(u - 260, v - 320)
            val inDisk = (120.5 - d).coerceIn(0.0, 1.0)
            val ring = max(cover(abs(d - 40)), cover(abs(d - 80)))
            val disk = 40 + 160 * ring
            val value = paint(px, py) ?: (inDisk * disk + (1 - inDisk) * paper).toInt()
            (value + rnd.nextInt(-3, 4)).coerceIn(0, 255).toByte()
        }
        return LumaFrame(big, big, luma)
    }

    @Test
    fun aHalfDegreeTurnOfTheFrameDoesNotFire() {
        val watch = NewHoleWatch()
        val fired = watch.feed(turned(1, 0.0), turned(2, 0.5), turned(3, 0.5))
        assertFalse(fired.any { it })
        assertTrue(watch.last!!.reason.startsWith("no blob"), watch.last?.reason)
    }

    @Test
    fun aHoleStillFiresAcrossAHalfDegreeTurn() {
        val watch = NewHoleWatch()
        val hole = hole(525, 75, r = 4, luma = 30)
        val (_, _, fired) = watch.feed(turned(1, 0.0), turned(2, 0.5, hole), turned(3, 0.5, hole))
        assertTrue(fired, watch.last?.reason)
        val found = watch.last!!.blobs.filter { it.area >= HOLE_MIN_AREA }
        assertEquals(1, found.size, watch.last?.reason)
        assertTrue(525 - found[0].x in 0 until found[0].width && 75 - found[0].y in 0 until found[0].height, "${found[0]} at ${found[0].x},${found[0].y}")
    }

    /**
     * [scene] plus gaussian-ish sensor noise of σ ≈ 12 (three uniform ±12 summed):
     * at σ ≈ 8 single frames left only 4-10 changed px against the 3×3 interval
     * compare, too few to show a difference; at 12 they leave ~90.
     */
    private fun noisy(seed: Int, paint: (x: Int, y: Int) -> Int? = { _, _ -> null }): LumaFrame {
        val clean = scene(seed, paint = paint)
        val rnd = Random(seed + 1000)
        return LumaFrame(size, size, ByteArray(size * size) { i ->
            val n = rnd.nextInt(-12, 13) + rnd.nextInt(-12, 13) + rnd.nextInt(-12, 13)
            ((clean.luma[i].toInt() and 0xFF) + n).coerceIn(0, 255).toByte()
        })
    }

    /** What the analyzer hands the watch: the running mean of the frames it got since the last sample. */
    private fun averaged(vararg frames: LumaFrame): LumaFrame {
        val mean = FrameMean()
        frames.forEach(mean::add)
        return assertNotNull(mean.take())
    }

    private fun noisySample(seed: Int, paint: (x: Int, y: Int) -> Int? = { _, _ -> null }) =
        averaged(noisy(3 * seed, paint), noisy(3 * seed + 1, paint), noisy(3 * seed + 2, paint))

    /**
     * [actual] is the mean of [frames]: under 5 % of its pixels more than 1 luma
     * off. (Older samples are moved by their tile shifts before averaging, so a
     * tile that won a noise-level ±1 px shift is not bit-exact; taking the latest
     * sample alone instead leaves ~40 % of the pixels off by 2+.)
     */
    private fun assertMeanOf(actual: LumaFrame?, vararg frames: LumaFrame) {
        val expected = averaged(*frames).luma
        val off = assertNotNull(actual).luma.indices.count { abs((actual.luma[it].toInt() and 0xFF) - (expected[it].toInt() and 0xFF)) > 1 }
        assertTrue(off < 0.05 * expected.size, "$off of ${expected.size} px off the mean")
    }

    @Test
    fun frameMeanAveragesAndStartsOverOnTake() {
        val mean = FrameMean()
        assertEquals(null, mean.take())
        listOf(10, 20, 31).forEach { v -> mean.add(LumaFrame(2, 1, byteArrayOf(v.toByte(), 250.toByte()), rotation = 90)) }
        val out = assertNotNull(mean.take())
        assertContentEquals(byteArrayOf(20, 250.toByte()), out.luma)
        assertEquals(90, out.rotation)
        assertEquals(null, mean.take())
        // A different grid starts the mean over rather than mixing two sizes.
        mean.add(LumaFrame(2, 1, byteArrayOf(0, 0)))
        mean.add(LumaFrame(1, 1, byteArrayOf(9)))
        assertContentEquals(byteArrayOf(9), assertNotNull(mean.take()).luma)
    }

    @Test
    fun theReferenceIsTheMeanOfTheLastThreeStillCleanSamples() {
        val watch = NewHoleWatch()
        // Low-noise frames: at σ ≈ 12 flat tiles pass as textured and win random
        // ±2 px shifts, which move the older samples' noise and break the pixel check.
        val s = (1..5).map { scene(it) }
        watch.feed(*s.toTypedArray())
        // The 5th sample was compared against the mean of the 2nd to 4th.
        assertMeanOf(watch.last?.reference, s[1], s[2], s[3])
    }

    @Test
    fun averagingCutsTheNoiseAndNoiseAloneNeverFires() {
        val single = changeMask(noisy(100), noisy(101))!!.count
        val watch = NewHoleWatch()
        val fired = (1..8).map { watch.offer(noisySample(it)) }
        assertFalse(fired.any { it })
        assertEquals(WatchOutcome.OTHER, watch.last?.outcome, watch.last?.reason)
        val averagedCount = changeMask(watch.last!!.reference!!, noisySample(9))!!.count
        println("noise σ≈12: single frames $single changed px, averaged $averagedCount")
        assertTrue(single >= 50, "single frames should show the noise: $single")
        assertTrue(averagedCount * 5 <= single, "averaged $averagedCount vs single $single")
    }

    @Test
    fun aHoleStillFiresThroughTheAveragedPath() {
        val watch = NewHoleWatch()
        repeat(4) { assertFalse(watch.offer(noisySample(it)), watch.last?.reason) }
        assertFalse(watch.offer(noisySample(4, hole(55, 50))))
        assertTrue(watch.offer(noisySample(5, hole(55, 50))), watch.last?.reason)
    }

    @Test
    fun aGlobalChangeStartsTheReferenceMeanOver() {
        val watch = NewHoleWatch()
        val cover: (Int, Int) -> Int? = { x, _ -> if (x < 40) 90 else null }
        watch.feed(scene(1), scene(2), scene(3))
        val b = (4..7).map { scene(it, paint = cover) }
        watch.feed(b[0], b[1]) // moving, then a global change: b[1] is the new reference
        assertTrue(watch.last!!.reason.startsWith("global change"), watch.last?.reason)
        watch.feed(b[2], b[3])
        // Compared against mean(b1, b2): nothing from before the change.
        assertMeanOf(watch.last?.reference, b[1], b[2])
    }

    @Test
    fun aResetAfterAFireStartsTheReferenceMeanOver() {
        val watch = NewHoleWatch()
        watch.feed(scene(1), scene(2), scene(3, paint = hole(55, 50)))
        assertTrue(watch.offer(scene(4, paint = hole(55, 50))))
        watch.reset()
        val c = (5..7).map { scene(it, paint = hole(55, 50)) }
        watch.feed(*c.toTypedArray())
        assertMeanOf(watch.last?.reference, c[0], c[1])
    }

    @Test
    fun aCreptSampleMovesTheReferenceMeanOntoTheNewFraming() {
        val watch = NewHoleWatch()
        watch.feed(scene(1), scene(2), scene(3), scene(4, shift = 2), scene(5, shift = 2))
        // Older samples were moved onto the creep before averaging: nothing left to align.
        assertTrue(watch.last!!.reason.contains("shift 0,0"), watch.last?.reason)
        assertEquals(WatchOutcome.OTHER, watch.last?.outcome)
    }

    @Test
    fun aReMeterStartsTheReferenceMeanOver() {
        val watch = NewHoleWatch()
        watch.feed(scene(1), scene(2), scene(3))
        watch.lightChanged()
        val after = (4..6).map { scene(it, brightness = 20) }
        watch.feed(*after.toTypedArray())
        assertMeanOf(watch.last?.reference, after[0], after[1])
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
