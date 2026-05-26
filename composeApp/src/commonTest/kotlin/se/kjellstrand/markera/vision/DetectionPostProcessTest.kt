package se.kjellstrand.markera.vision

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class DetectionPostProcessTest {

    @Test
    fun `filterByConfidence drops detections below threshold`() {
        val input = listOf(
            RawDetection(10f, 10f, 5f, 5f, conf = 0.2f),
            RawDetection(20f, 20f, 5f, 5f, conf = 0.5f),
            RawDetection(30f, 30f, 5f, 5f, conf = 0.9f),
        )
        val kept = filterByConfidence(input, 0.4f)
        assertEquals(2, kept.size)
        assertTrue(kept.all { it.conf >= 0.4f })
    }

    @Test
    fun `NMS suppresses the lower-confidence box when two overlap heavily`() {
        val high = RawDetection(50f, 50f, 20f, 20f, conf = 0.9f)
        val nearDuplicate = RawDetection(52f, 51f, 20f, 20f, conf = 0.6f)
        val kept = nonMaxSuppression(listOf(high, nearDuplicate), iouThreshold = 0.5f)
        assertEquals(1, kept.size)
        assertEquals(0.9f, kept[0].conf, 0.0001f)
    }

    @Test
    fun `NMS keeps both boxes when they do not overlap`() {
        val a = RawDetection(10f, 10f, 8f, 8f, conf = 0.9f)
        val b = RawDetection(80f, 80f, 8f, 8f, conf = 0.8f)
        val kept = nonMaxSuppression(listOf(a, b), iouThreshold = 0.5f)
        assertEquals(2, kept.size)
    }

    @Test
    fun `mapToImageSpace round-trips a centre box on a square source`() {
        val raw = RawDetection(cx = 160f, cy = 160f, w = 32f, h = 32f, conf = 0.9f)
        val mapped = mapToImageSpace(listOf(raw), inputSize = 320, srcWidth = 640, srcHeight = 640)
        assertEquals(1, mapped.size)
        val d = mapped[0]
        // 320-pixel input maps 1:1 to 640-pixel image at 2x; centre (160, 160) → image (320, 320),
        // half-size 16 model-px → 32 image-px.
        assertEquals(288f, d.left, 0.1f)
        assertEquals(288f, d.top, 0.1f)
        assertEquals(352f, d.right, 0.1f)
        assertEquals(352f, d.bottom, 0.1f)
    }

    @Test
    fun `mapToImageSpace undoes letterbox padding for landscape source`() {
        // 800x400 source letterboxed into 320x320: scale = 0.4, newW = 320, newH = 160,
        // padY = 80, padX = 0. A centred box at model (160, 160) → image centre (400, 200).
        val raw = RawDetection(cx = 160f, cy = 160f, w = 40f, h = 40f, conf = 0.9f)
        val mapped = mapToImageSpace(listOf(raw), inputSize = 320, srcWidth = 800, srcHeight = 400)
        assertEquals(1, mapped.size)
        val d = mapped[0]
        assertEquals(350f, d.left, 0.1f)
        assertEquals(150f, d.top, 0.1f)
        assertEquals(450f, d.right, 0.1f)
        assertEquals(250f, d.bottom, 0.1f)
    }

    @Test
    fun `mapToImageSpace returns empty list when source dimensions are zero`() {
        val raw = RawDetection(0f, 0f, 0f, 0f, 0.5f)
        val mapped = mapToImageSpace(listOf(raw), inputSize = 320, srcWidth = 0, srcHeight = 100)
        assertTrue(mapped.isEmpty())
    }
}
