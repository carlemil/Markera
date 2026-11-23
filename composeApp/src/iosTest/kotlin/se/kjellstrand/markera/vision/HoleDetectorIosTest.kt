@file:OptIn(ExperimentalForeignApi::class)

package se.kjellstrand.markera.vision

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSData
import platform.Foundation.create
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The Kotlin half of the ORT bridge: the tensor goes over as a pinned pointer, close is forwarded. */
class HoleDetectorIosTest {

    private class FakeModel(private val rows: FloatArray) : HoleModel {
        var seen: List<Float> = emptyList()
        var seenFloats = -1
        var seenSize = -1
        var closes = 0

        override fun run(input: CPointer<FloatVar>, inputFloats: Int, inputSize: Int): NSData {
            seenFloats = inputFloats
            seenSize = inputSize
            seen = listOf(input[0], input[1], input[inputFloats - 1])
            if (rows.isEmpty()) return NSData()
            return rows.usePinned { NSData.create(bytes = it.addressOf(0), length = (rows.size * 4).toULong()) }
        }

        override fun close() {
            closes++
        }
    }

    @AfterTest
    fun unbind() {
        holeModel = null
    }

    @Test
    fun detectReadsTheTensorInPlaceAndParsesTheRows() = runBlocking {
        val size = 4
        // One box (x1, y1, x2, y2, conf, class) and a zero-padded slot.
        val model = FakeModel(floatArrayOf(1f, 2f, 3f, 6f, 0.9f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))
        holeModel = model
        val input = FloatArray(3 * size * size) { it.toFloat() }

        val rows = HoleDetector("", size).detect(input)

        assertEquals(input.size, model.seenFloats)
        assertEquals(size, model.seenSize)
        assertEquals(listOf(0f, 1f, (input.size - 1).toFloat()), model.seen)
        assertEquals(listOf(RawDetection(cx = 2f, cy = 4f, w = 2f, h = 4f, conf = 0.9f)), rows)
    }

    @Test
    fun aWrongSizedTensorNeverReachesTheModel() {
        val model = FakeModel(FloatArray(0))
        holeModel = model
        assertFailsWith<IllegalArgumentException> {
            runBlocking { HoleDetector("", 4).detect(FloatArray(10)) }
        }
        assertEquals(-1, model.seenFloats)
    }

    @Test
    fun anEmptyOutputIsAnError() {
        holeModel = FakeModel(FloatArray(0))
        val e = assertFailsWith<IllegalStateException> {
            runBlocking { HoleDetector("", 2).detect(FloatArray(12)) }
        }
        assertTrue(e.message!!.contains("hole model failed"))
    }

    @Test
    fun closeReleasesTheHostSession() {
        val model = FakeModel(FloatArray(0))
        holeModel = model
        HoleDetector("", 4).close()
        assertEquals(1, model.closes)
    }
}
