package se.kjellstrand.markera.vision

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Synthetic end-to-end: build a known camera pose looking at a planar
 * circle, derive the imaged ellipse analytically (no fitting noise),
 * call [recoverCirclePose], assert pose recovery accuracy.
 */
class PlanarCirclePoseTest {

    private val intrinsics = CameraIntrinsics(
        fx = 1500f, fy = 1500f, cx = 960f, cy = 540f,
        sensorWidthPx = 1920f, sensorHeightPx = 1080f,
        sensorRotationDeg = 0,
        source = CameraIntrinsics.Source.CALIBRATED,
    )

    private val worldRadiusMm = 100.0

    /**
     * For a camera at origin looking down +z, place a circle on a plane
     * whose normal is `n` (tilt about world Y by [tiltRad]). Centre of
     * circle at depth `dz` along +z, offset laterally by (dx, dy).
     */
    private fun syntheticEllipse(
        tiltRad: Double,
        dz: Double,
        dx: Double = 0.0,
        dy: Double = 0.0,
    ): Pair<TargetCalibration, Triple<Vec3, Vec3, Vec3>> {
        // World plane basis. Rotate about Y by tiltRad:
        //   r1 = (cos t, 0, sin t)
        //   r2 = (0, 1, 0)
        //   n  = (-sin t, 0, cos t) (right-hand: r1 × r2)
        // Pick n so it points toward the camera (n.z < 0) → use cos t with
        // sign flip: for the camera looking +z, the visible-plane outward
        // normal should be negative-z. Use n = (sin t, 0, -cos t).
        val r1 = Vec3(cos(tiltRad), 0.0, sin(tiltRad))
        val r2 = Vec3(0.0, 1.0, 0.0)
        val n = r1.cross(r2)  // (-sin, 0, cos) for the right-handed orientation
        // We want n.z < 0; if not, negate r2 to flip the basis (still orthonormal).
        val (r1Final, r2Final, nFinal) = if (n.z < 0) {
            Triple(r1, r2, n)
        } else {
            Triple(r1, -r2, -n)
        }
        val t = Vec3(dx, dy, dz)
        val K = intrinsics.toK()
        val Hwi = mat3Multiply(K, Mat3.fromColumns(r1Final, r2Final, t))
        // Conic in image: C = H^-T diag(1, 1, -R^2) H^-1
        val Hwii = mat3Inverse(Hwi)!!
        val Hwiit = mat3Transpose(Hwii)
        val D = Mat3.of(
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, -worldRadiusMm * worldRadiusMm,
        )
        val C = mat3Multiply(Hwiit, mat3Multiply(D, Hwii))
        val cal = conicToCalibration(C)
        return cal to Triple(r1Final, r2Final, nFinal)
    }

    /** Convert a 3x3 image-conic matrix to (cx, cy, a, b, theta) form. */
    private fun conicToCalibration(C: Mat3): TargetCalibration {
        val a11 = C[0, 0]
        val a12 = C[0, 1]
        val a22 = C[1, 1]
        val a13 = C[0, 2]
        val a23 = C[1, 2]
        val a33 = C[2, 2]
        // Centre: solve [[a11 a12][a12 a22]] [cx cy] = -[a13, a23].
        val det2 = a11 * a22 - a12 * a12
        val cx = (-a13 * a22 + a23 * a12) / det2
        val cy = (-a11 * a23 + a12 * a13) / det2
        // Constant at centre.
        val k = a11 * cx * cx + 2.0 * a12 * cx * cy + a22 * cy * cy +
            2.0 * a13 * cx + 2.0 * a23 * cy + a33
        // Eigenvalues of [[a11 a12][a12 a22]].
        val trace = a11 + a22
        val disc = sqrt((a11 - a22) * (a11 - a22) + 4.0 * a12 * a12)
        val lA = (trace + disc) / 2.0  // larger
        val lB = (trace - disc) / 2.0  // smaller
        // a is along smaller eigenvalue (since semi-axis = sqrt(-k/lambda)).
        val aSq = -k / lB
        val bSq = -k / lA
        val a = sqrt(aSq)
        val b = sqrt(bSq)
        // Rotation: angle of eigenvector for lB (the longer axis).
        // [[a11 a12][a12 a22]] v = lB v → v = (a12, lB - a11), or (lB - a22, a12).
        val vx = a12
        val vy = lB - a11
        val theta = atan2(vy, vx)
        return TargetCalibration(
            centerX = cx.toFloat(),
            centerY = cy.toFloat(),
            semiMajorPx = a.toFloat(),
            semiMinorPx = b.toFloat(),
            rotationRad = theta.toFloat(),
            mmPerPx = worldRadiusMm / a,
            confidence = 1f,
        )
    }

    private fun assertVec3Near(expected: Vec3, actual: Vec3, eps: Double = 0.01) {
        assertEquals(expected.x, actual.x, eps, "x")
        assertEquals(expected.y, actual.y, eps, "y")
        assertEquals(expected.z, actual.z, eps, "z")
    }

    @Test
    fun `recovers known pose at moderate tilt`() {
        val tilt = 30.0 * PI / 180.0
        val dz = 500.0
        val (cal, truth) = syntheticEllipse(tilt, dz)
        val (rTrue1, _, nTrue) = truth
        val pose = recoverCirclePose(cal, intrinsics, worldRadiusMm)
        assertNotNull(pose, "pose recovery should succeed at 30 deg tilt")
        pose!!
        // Normal direction (up to ±). Synth uses our n; recovered should match.
        val cosAngle = abs(pose.n.dot(nTrue))
        assertTrue(cosAngle > 0.999, "normal angle cos=$cosAngle should be ~1")
        // Centre depth within 2%.
        assertEquals(dz, pose.c0.z, dz * 0.02)
        // Lateral within 1mm.
        assertEquals(0.0, pose.c0.x, 1.0)
        assertEquals(0.0, pose.c0.y, 1.0)
    }

    @Test
    fun `recovers steep tilt`() {
        val tilt = 50.0 * PI / 180.0
        val dz = 600.0
        val (cal, truth) = syntheticEllipse(tilt, dz)
        val (_, _, nTrue) = truth
        val pose = recoverCirclePose(cal, intrinsics, worldRadiusMm)
        assertNotNull(pose); pose!!
        val cosAngle = abs(pose.n.dot(nTrue))
        assertTrue(cosAngle > 0.995, "normal angle cos=$cosAngle")
        assertEquals(dz, pose.c0.z, dz * 0.05)
    }

    @Test
    fun `true centre differs from ellipse centroid under tilt`() {
        val tilt = 35.0 * PI / 180.0
        val dz = 500.0
        val dx = 60.0  // lateral offset so centroid bias is visible
        val (cal, _) = syntheticEllipse(tilt, dz, dx, 0.0)
        val pose = recoverCirclePose(cal, intrinsics, worldRadiusMm)
        assertNotNull(pose); pose!!
        // The ellipse centroid (cal.centerX) and the true projected centre
        // (pose.uTrue) should disagree by several pixels for this geometry.
        val gap = abs(pose.uTrue - cal.centerX)
        assertTrue(gap > 1.0, "expected uTrue!=centroid; got gap=$gap")
        // The recovered c0 should still be near the true centre (dx, 0, dz).
        assertEquals(dx, pose.c0.x, 2.0)
        assertEquals(dz, pose.c0.z, dz * 0.05)
    }
}
