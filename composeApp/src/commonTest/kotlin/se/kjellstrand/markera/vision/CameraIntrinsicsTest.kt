package se.kjellstrand.markera.vision

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.Test

class CameraIntrinsicsTest {

    private fun sensorIntrinsics(rotation: Int) = CameraIntrinsics(
        fx = 3000f, fy = 3010f,
        cx = 2000f, cy = 1500f,
        sensorWidthPx = 4000f, sensorHeightPx = 3000f,
        sensorRotationDeg = rotation,
        source = CameraIntrinsics.Source.CALIBRATED,
    )

    @Test
    fun `no rotation scales uniformly`() {
        val src = sensorIntrinsics(0)
        // Bitmap 4:3, half the sensor's size.
        val out = src.scaledToBitmap(2000, 1500)
        assertNotNull(out); out!!
        assertEquals(1500f, out.fx, 1e-3f)
        assertEquals(1505f, out.fy, 1e-3f)
        assertEquals(1000f, out.cx, 1e-3f)
        assertEquals(750f, out.cy, 1e-3f)
    }

    @Test
    fun `90 degree rotation swaps fx fy and reflects principal point`() {
        val src = sensorIntrinsics(90)
        // Display orientation is portrait: 3000x4000. Bitmap at full res.
        val out = src.scaledToBitmap(3000, 4000)
        assertNotNull(out); out!!
        assertEquals(3010f, out.fx, 1e-3f)
        assertEquals(3000f, out.fy, 1e-3f)
        assertEquals(2999f - 1500f, out.cx, 1e-3f)  // sensorH - 1 - cy
        assertEquals(2000f, out.cy, 1e-3f)
    }

    @Test
    fun `270 degree rotation flips principal point through other axis`() {
        val src = sensorIntrinsics(270)
        val out = src.scaledToBitmap(3000, 4000)
        assertNotNull(out); out!!
        assertEquals(3010f, out.fx, 1e-3f)
        assertEquals(3000f, out.fy, 1e-3f)
        assertEquals(1500f, out.cx, 1e-3f)  // cy
        assertEquals(3999f - 2000f, out.cy, 1e-3f)  // sensorW - 1 - cx
    }

    @Test
    fun `180 degree rotation mirrors principal point`() {
        val src = sensorIntrinsics(180)
        val out = src.scaledToBitmap(4000, 3000)
        assertNotNull(out); out!!
        assertEquals(3000f, out.fx, 1e-3f)
        assertEquals(3010f, out.fy, 1e-3f)
        assertEquals(3999f - 2000f, out.cx, 1e-3f)
        assertEquals(2999f - 1500f, out.cy, 1e-3f)
    }

    @Test
    fun `FILL_CENTER square bitmap from 3-by-4 portrait sensor crops vertical excess`() {
        // 90 deg sensor → display portrait 3000x4000, fx=3010, fy=3000,
        // cx=1499, cy=2000 (after rotation in our test helper).
        val src = sensorIntrinsics(90)
        // Square bitmap 1080x1080 from FILL_CENTER on portrait preview:
        // scale = max(1080/3000, 1080/4000) = 0.36
        // scaled sensor: 1080 x 1440, crop 180 px from top and bottom
        val out = src.scaledToBitmap(1080, 1080)
        assertNotNull(out); out!!
        assertEquals(3010f * 0.36f, out.fx, 1e-2f)
        assertEquals(3000f * 0.36f, out.fy, 1e-2f)
        assertEquals(1499f * 0.36f - 0f, out.cx, 1e-2f)
        assertEquals(2000f * 0.36f - 180f, out.cy, 1e-2f)
        assertEquals(1080f, out.sensorWidthPx, 0f)
        assertEquals(1080f, out.sensorHeightPx, 0f)
        assertEquals(0, out.sensorRotationDeg)
    }

    @Test
    fun `FILL_CENTER 16-by-9 bitmap from 4-by-3 landscape sensor crops horizontal excess`() {
        val src = sensorIntrinsics(0)  // landscape 4000x3000
        // 16:9 bitmap 1920x1080 from FILL_CENTER on landscape preview:
        // scale = max(1920/4000, 1080/3000) = max(0.48, 0.36) = 0.48
        // scaled sensor: 1920 x 1440, crop 180 px from top and bottom
        val out = src.scaledToBitmap(1920, 1080)
        assertNotNull(out); out!!
        assertEquals(3000f * 0.48f, out.fx, 1e-2f)
        assertEquals(3010f * 0.48f, out.fy, 1e-2f)
        assertEquals(2000f * 0.48f, out.cx, 1e-2f)
        assertEquals(1500f * 0.48f - 180f, out.cy, 1e-2f)
    }

    @Test
    fun `toK builds standard pinhole matrix`() {
        val intr = CameraIntrinsics(
            fx = 100f, fy = 110f, cx = 320f, cy = 240f,
            sensorWidthPx = 640f, sensorHeightPx = 480f,
            sensorRotationDeg = 0, source = CameraIntrinsics.Source.CALIBRATED,
        )
        val k = intr.toK()
        assertEquals(100.0, k[0, 0], 0.0)
        assertEquals(110.0, k[1, 1], 0.0)
        assertEquals(320.0, k[0, 2], 0.0)
        assertEquals(240.0, k[1, 2], 0.0)
        assertEquals(1.0, k[2, 2], 0.0)
        assertEquals(0.0, k[1, 0], 0.0)
    }
}
