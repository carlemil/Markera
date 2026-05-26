package se.kjellstrand.markera.vision

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pose of a 3D circle whose perimeter projects to the fitted target ellipse.
 *
 * - [n]  : plane normal in camera coords (unit). Convention: points toward
 *          the camera (n.z < 0), so the target's front face is visible.
 * - [r1, r2] : orthonormal in-plane basis (right-handed with n).
 * - [c0] : 3D centre of the circle in camera coords (mm). c0.z > 0.
 * - [uTrue, vTrue] : projection of c0 through K. This is the TRUE imaged
 *          centre of the 3D circle — distinct from the centroid of the
 *          fitted ellipse for tilted views (the affine warp uses the
 *          centroid; this is the fix).
 */
data class CirclePose(
    val n: Vec3,
    val r1: Vec3,
    val r2: Vec3,
    val c0: Vec3,
    val uTrue: Double,
    val vTrue: Double,
)

/**
 * Closed-form recovery of [CirclePose] from a single fitted ellipse + camera
 * intrinsics, given the world-plane circle radius. Algorithm follows the
 * circular-cross-section construction for an oblique elliptical cone:
 *
 * 1. Build conic matrix M of the imaged ellipse.
 * 2. Q = Kᵀ M K — cone matrix in camera coords.
 * 3. Symmetric eigendecomposition Q = V Λ Vᵀ, normalise sign so exactly
 *    one eigenvalue is negative (λ₁ ≥ λ₂ > 0 > λ₃).
 * 4. Plane normal candidates: n = s₁·e₁ ± s₂·e₃, with
 *    s₁ = √((λ₁-λ₂)/(λ₁-λ₃)), s₂ = √((λ₂-λ₃)/(λ₁-λ₃)).
 * 5. For each candidate: solve t (= circle centre in camera coords) from
 *    the constraints  rᵢᵀ Q t = 0  and  tᵀ Q t = -α R² with α = r₁ᵀ Q r₁.
 *    Result: t = γ · Q⁻¹ n with γ² = -α R² / (nᵀ Q⁻¹ n). Pick the sign
 *    of γ that yields t.z > 0 (point in front of camera).
 * 6. Sanity: reject the candidate if (uTrue, vTrue) lands outside the
 *    fitted-ellipse axis-aligned bbox. Of the surviving candidate(s),
 *    prefer the one with n.z < 0 (target normal facing camera).
 *
 * Returns null on degeneracy (eigenvalues wrong sign pattern, singular Q,
 * both branches rejected).
 */
fun recoverCirclePose(
    calibration: TargetCalibration,
    intrinsics: CameraIntrinsics,
    worldRadiusMm: Double = TARGET_BLACK_RING_RADIUS_MM,
): CirclePose? {
    val cx = calibration.centerX.toDouble()
    val cy = calibration.centerY.toDouble()
    val a = calibration.semiMajorPx.toDouble()
    val b = calibration.semiMinorPx.toDouble()
    val theta = calibration.rotationRad.toDouble()
    if (a <= 0.0 || b <= 0.0) return null

    val M = buildEllipseConic(cx, cy, a, b, theta)

    val K = intrinsics.toK()
    val KT = mat3Transpose(K)
    val Q = mat3Multiply(KT, mat3Multiply(M, K))

    val eig = jacobiSymmetric3(Q)
    val (lambdas, vecs) = normalizeOneNegative(eig.values, eig.vectors) ?: return null
    val l1 = lambdas[0]
    val l2 = lambdas[1]
    val l3 = lambdas[2]
    if (l1 <= 0.0 || l2 <= 0.0 || l3 >= 0.0) return null
    if (l1 < 1e-12 || abs(l3) < 1e-12) return null

    val denom = l1 - l3
    if (denom <= 0.0) return null
    val s1 = sqrt((l1 - l2) / denom)
    val s2 = sqrt((l2 - l3) / denom)

    val e1 = Vec3(vecs[0, 0], vecs[1, 0], vecs[2, 0])
    val e3 = Vec3(vecs[0, 2], vecs[1, 2], vecs[2, 2])
    val Qinv = mat3Inverse(Q) ?: return null

    val cosT = cos(theta)
    val sinT = sin(theta)
    val extentX = sqrt(a * a * cosT * cosT + b * b * sinT * sinT)
    val extentY = sqrt(a * a * sinT * sinT + b * b * cosT * cosT)

    fun tryBranch(branchSign: Double): CirclePose? {
        val n = (e1 * s1 + e3 * (branchSign * s2)).normalized()
        val r1 = chooseInPlaneBasis(n)
        val r2 = r1.cross(n).normalized()

        val alpha = r1.dot(mat3MulVec(Q, r1))
        val nQinvn = n.dot(mat3MulVec(Qinv, n))
        if (abs(nQinvn) < 1e-20) return null
        val gammaSq = -alpha * worldRadiusMm * worldRadiusMm / nQinvn
        if (gammaSq <= 0.0) return null
        val gammaMag = sqrt(gammaSq)
        val baseT = mat3MulVec(Qinv, n)

        for (gammaSign in doubleArrayOf(1.0, -1.0)) {
            val c0 = baseT * (gammaSign * gammaMag)
            if (c0.z <= 0.0) continue
            val Kc0 = mat3MulVec(K, c0)
            if (Kc0.z == 0.0) continue
            val uTrue = Kc0.x / Kc0.z
            val vTrue = Kc0.y / Kc0.z
            if (abs(uTrue - cx) > extentX + 1.0) continue
            if (abs(vTrue - cy) > extentY + 1.0) continue
            return CirclePose(n, r1, r2, c0, uTrue, vTrue)
        }
        return null
    }

    val candidates = listOfNotNull(tryBranch(+1.0), tryBranch(-1.0))
    if (candidates.isEmpty()) return null
    // The "front-facing" branch has plane normal pointing toward the
    // camera (camera looks along +z, so the visible plane's outward
    // normal points along -z). Prefer that one if present.
    return candidates.firstOrNull { it.n.z < 0.0 } ?: candidates.first()
}

private fun buildEllipseConic(cx: Double, cy: Double, a: Double, b: Double, theta: Double): Mat3 {
    val cosT = cos(theta)
    val sinT = sin(theta)
    val ia2 = 1.0 / (a * a)
    val ib2 = 1.0 / (b * b)
    val cc = cosT * cosT
    val ss = sinT * sinT
    val A = cc * ia2 + ss * ib2
    val B = cosT * sinT * (ia2 - ib2)
    val D = ss * ia2 + cc * ib2
    val Mx = -A * cx - B * cy
    val My = -B * cx - D * cy
    val F = A * cx * cx + 2.0 * B * cx * cy + D * cy * cy - 1.0
    return Mat3.of(
        A, B, Mx,
        B, D, My,
        Mx, My, F,
    )
}

private fun normalizeOneNegative(
    values: DoubleArray,
    vectors: Mat3,
): Pair<DoubleArray, Mat3>? {
    val negCount = values.count { it < 0.0 }
    return when (negCount) {
        1 -> values to vectors
        2 -> {
            // Negate all eigenvalues so 2 become positive and 1 becomes
            // negative. Re-sort descending: new[i] = -old[2-i].
            val newValues = DoubleArray(3) { -values[2 - it] }
            val newVecs = Mat3.of(
                vectors[0, 2], vectors[0, 1], vectors[0, 0],
                vectors[1, 2], vectors[1, 1], vectors[1, 0],
                vectors[2, 2], vectors[2, 1], vectors[2, 0],
            )
            newValues to newVecs
        }
        else -> null
    }
}

private fun chooseInPlaneBasis(n: Vec3): Vec3 {
    // r1 = projection of the camera x-axis onto the target plane. For a
    // front-facing target this collapses to (1, 0, 0) and the rectified
    // output preserves the photo's left-right orientation; r2 = r1 × n
    // then aligns with the image's down direction (so world Y projects
    // downward in the output, matching the original).
    val xAxis = Vec3(1.0, 0.0, 0.0)
    val projX = xAxis - n * xAxis.dot(n)
    if (projX.norm() > 0.05) return projX.normalized()
    // n is nearly along camera x — fall back to the y-axis projection.
    val yAxis = Vec3(0.0, 1.0, 0.0)
    val projY = yAxis - n * yAxis.dot(n)
    return projY.normalized()
}
