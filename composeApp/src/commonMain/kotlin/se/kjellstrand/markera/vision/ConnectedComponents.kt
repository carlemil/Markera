package se.kjellstrand.markera.vision

/**
 * Two-pass 4-connected component labelling on a "dark" mask derived from
 * an 8-bit grayscale buffer (foreground = pixels with value <= threshold).
 *
 * The output [CcResult.labels] is row-major, same dimensions as the input,
 * with 0 for background and a positive integer label per blob (labels
 * stable but not contiguous — only matters for perimeter scans, which
 * compare against the chosen label).
 */
data class Blob(
    val label: Int,
    val pixelCount: Int,
    val sumX: Long,
    val sumY: Long,
    val minX: Int,
    val maxX: Int,
    val minY: Int,
    val maxY: Int,
) {
    val centroidX: Float get() = sumX.toFloat() / pixelCount
    val centroidY: Float get() = sumY.toFloat() / pixelCount
    val width: Int get() = maxX - minX + 1
    val height: Int get() = maxY - minY + 1
}

data class CcResult(val labels: IntArray, val blobs: List<Blob>)

fun findDarkBlobs(
    gray: ByteArray,
    width: Int,
    height: Int,
    threshold: Int,
    minArea: Int,
): CcResult {
    val labels = IntArray(width * height)
    if (width <= 0 || height <= 0) return CcResult(labels, emptyList())

    // Union-find over labels.
    val parent = ArrayList<Int>()
    val rank = ArrayList<Int>()
    parent.add(0); rank.add(0) // index 0 = background sentinel

    fun newLabel(): Int {
        parent.add(parent.size)
        rank.add(0)
        return parent.size - 1
    }
    fun find(x: Int): Int {
        var cur = x
        while (parent[cur] != cur) {
            parent[cur] = parent[parent[cur]]
            cur = parent[cur]
        }
        return cur
    }
    fun union(a: Int, b: Int) {
        val ra = find(a); val rb = find(b)
        if (ra == rb) return
        when {
            rank[ra] < rank[rb] -> parent[ra] = rb
            rank[ra] > rank[rb] -> parent[rb] = ra
            else -> { parent[rb] = ra; rank[ra] = rank[ra] + 1 }
        }
    }

    // Pass 1 — assign provisional labels with 4-connectivity (north + west).
    for (y in 0 until height) {
        for (x in 0 until width) {
            val idx = y * width + x
            val v = gray[idx].toInt() and 0xFF
            if (v > threshold) continue
            val north = if (y > 0) labels[idx - width] else 0
            val west = if (x > 0) labels[idx - 1] else 0
            when {
                north != 0 && west != 0 -> {
                    labels[idx] = north
                    if (north != west) union(north, west)
                }
                north != 0 -> labels[idx] = north
                west != 0 -> labels[idx] = west
                else -> labels[idx] = newLabel()
            }
        }
    }

    // Pass 2 — flatten to canonical roots and accumulate per-blob stats.
    val stats = HashMap<Int, IntArray>() // root -> [count, minX, maxX, minY, maxY], sums in two Longs below
    val sumX = HashMap<Int, Long>()
    val sumY = HashMap<Int, Long>()
    for (y in 0 until height) {
        for (x in 0 until width) {
            val idx = y * width + x
            val l = labels[idx]
            if (l == 0) continue
            val r = find(l)
            labels[idx] = r
            val s = stats.getOrPut(r) { intArrayOf(0, Int.MAX_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MIN_VALUE) }
            s[0]++
            if (x < s[1]) s[1] = x
            if (x > s[2]) s[2] = x
            if (y < s[3]) s[3] = y
            if (y > s[4]) s[4] = y
            sumX[r] = (sumX[r] ?: 0L) + x
            sumY[r] = (sumY[r] ?: 0L) + y
        }
    }

    val blobs = ArrayList<Blob>(stats.size)
    for ((root, s) in stats) {
        if (s[0] < minArea) continue
        blobs += Blob(
            label = root,
            pixelCount = s[0],
            sumX = sumX[root] ?: 0L,
            sumY = sumY[root] ?: 0L,
            minX = s[1], maxX = s[2],
            minY = s[3], maxY = s[4],
        )
    }
    return CcResult(labels, blobs)
}
