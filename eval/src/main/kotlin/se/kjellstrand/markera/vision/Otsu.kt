package se.kjellstrand.markera.vision

/**
 * Otsu's threshold on an 8-bit grayscale buffer. Picks the intensity that
 * maximises between-class variance (foreground = dark, background = light
 * by convention here; the returned value is the threshold itself — pixels
 * with value <= threshold are foreground).
 *
 * Returns 0 if the buffer is empty or single-valued.
 */
fun otsuThreshold(gray: ByteArray, width: Int, height: Int): Int {
    val n = width * height
    if (n <= 0 || gray.size < n) return 0

    val hist = IntArray(256)
    for (i in 0 until n) hist[gray[i].toInt() and 0xFF]++

    var sumAll = 0L
    for (t in 0 until 256) sumAll += t.toLong() * hist[t]

    var sumB = 0L
    var wB = 0
    var maxBetween = -1.0
    var bestT = 0

    for (t in 0 until 256) {
        wB += hist[t]
        if (wB == 0) continue
        val wF = n - wB
        if (wF == 0) break

        sumB += t.toLong() * hist[t]
        val mB = sumB.toDouble() / wB
        val mF = (sumAll - sumB).toDouble() / wF
        val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
        if (between > maxBetween) {
            maxBetween = between
            bestT = t
        }
    }
    return bestT
}
