package se.kjellstrand.markera.vision

import kotlin.math.sqrt

/**
 * Row-major 3x3 matrix of doubles. Backing array order is
 * `[m00, m01, m02, m10, m11, m12, m20, m21, m22]` — matches
 * `android.graphics.Matrix.getValues / setValues`.
 */
class Mat3(val data: DoubleArray) {
    init { require(data.size == 9) { "Mat3 needs 9 entries, got ${data.size}" } }

    operator fun get(r: Int, c: Int): Double = data[r * 3 + c]

    fun copy(): Mat3 = Mat3(data.copyOf())

    fun toFloatArrayRowMajor(): FloatArray = FloatArray(9) { data[it].toFloat() }

    companion object {
        fun identity(): Mat3 = Mat3(
            doubleArrayOf(
                1.0, 0.0, 0.0,
                0.0, 1.0, 0.0,
                0.0, 0.0, 1.0,
            )
        )

        fun of(
            m00: Double, m01: Double, m02: Double,
            m10: Double, m11: Double, m12: Double,
            m20: Double, m21: Double, m22: Double,
        ): Mat3 = Mat3(doubleArrayOf(m00, m01, m02, m10, m11, m12, m20, m21, m22))

        fun fromColumns(c0: Vec3, c1: Vec3, c2: Vec3): Mat3 = of(
            c0.x, c1.x, c2.x,
            c0.y, c1.y, c2.y,
            c0.z, c1.z, c2.z,
        )
    }
}

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3): Vec3 = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3): Vec3 = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double): Vec3 = Vec3(x * s, y * s, z * s)
    operator fun unaryMinus(): Vec3 = Vec3(-x, -y, -z)
    fun dot(o: Vec3): Double = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3): Vec3 =
        Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun norm(): Double = sqrt(x * x + y * y + z * z)
    fun normalized(): Vec3 {
        val n = norm()
        require(n > 0.0) { "cannot normalize zero vector" }
        return Vec3(x / n, y / n, z / n)
    }
}

fun mat3Multiply(a: Mat3, b: Mat3): Mat3 {
    val out = DoubleArray(9)
    for (i in 0..2) {
        for (j in 0..2) {
            var s = 0.0
            for (k in 0..2) s += a[i, k] * b[k, j]
            out[i * 3 + j] = s
        }
    }
    return Mat3(out)
}

fun mat3Transpose(m: Mat3): Mat3 = Mat3.of(
    m[0, 0], m[1, 0], m[2, 0],
    m[0, 1], m[1, 1], m[2, 1],
    m[0, 2], m[1, 2], m[2, 2],
)

fun mat3MulVec(m: Mat3, v: Vec3): Vec3 = Vec3(
    m[0, 0] * v.x + m[0, 1] * v.y + m[0, 2] * v.z,
    m[1, 0] * v.x + m[1, 1] * v.y + m[1, 2] * v.z,
    m[2, 0] * v.x + m[2, 1] * v.y + m[2, 2] * v.z,
)

fun det3(
    a: Double, b: Double, c: Double,
    d: Double, e: Double, f: Double,
    g: Double, h: Double, i: Double,
): Double = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)

fun mat3Det(m: Mat3): Double = det3(
    m[0, 0], m[0, 1], m[0, 2],
    m[1, 0], m[1, 1], m[1, 2],
    m[2, 0], m[2, 1], m[2, 2],
)

/** Cofactor-based inverse. Returns null if `m` is singular within [eps]. */
fun mat3Inverse(m: Mat3, eps: Double = 1e-15): Mat3? {
    val det = mat3Det(m)
    if (det == 0.0 || kotlin.math.abs(det) < eps) return null
    val inv = 1.0 / det
    // Cofactor matrix transposed → adjugate.
    return Mat3.of(
        (m[1, 1] * m[2, 2] - m[1, 2] * m[2, 1]) * inv,
        -(m[0, 1] * m[2, 2] - m[0, 2] * m[2, 1]) * inv,
        (m[0, 1] * m[1, 2] - m[0, 2] * m[1, 1]) * inv,
        -(m[1, 0] * m[2, 2] - m[1, 2] * m[2, 0]) * inv,
        (m[0, 0] * m[2, 2] - m[0, 2] * m[2, 0]) * inv,
        -(m[0, 0] * m[1, 2] - m[0, 2] * m[1, 0]) * inv,
        (m[1, 0] * m[2, 1] - m[1, 1] * m[2, 0]) * inv,
        -(m[0, 0] * m[2, 1] - m[0, 1] * m[2, 0]) * inv,
        (m[0, 0] * m[1, 1] - m[0, 1] * m[1, 0]) * inv,
    )
}

fun mat3Scaled(m: Mat3, s: Double): Mat3 {
    val out = DoubleArray(9) { m.data[it] * s }
    return Mat3(out)
}
