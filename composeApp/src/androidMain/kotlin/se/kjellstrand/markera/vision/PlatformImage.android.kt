package se.kjellstrand.markera.vision

import android.graphics.Bitmap
import kotlin.math.min

actual typealias PlatformImage = Bitmap

// Shadowed by Bitmap's own width/height at every Android call site (the member
// wins) — these exist so common code has something to resolve against, and so
// iOS gets a real implementation.
actual val PlatformImage.width: Int get() = getWidth()

actual val PlatformImage.height: Int get() = getHeight()

actual fun PlatformImage.centerSquare(): PlatformImage {
    if (getWidth() == getHeight()) return this
    val side = min(getWidth(), getHeight())
    return Bitmap.createBitmap(this, (getWidth() - side) / 2, (getHeight() - side) / 2, side, side)
}

actual fun PlatformImage.argb(width: Int, height: Int): IntArray {
    val scaled = if (width == getWidth() && height == getHeight()) {
        this
    } else {
        Bitmap.createScaledBitmap(this, width, height, true)
    }
    val out = IntArray(width * height)
    scaled.getPixels(out, 0, width, 0, 0, width, height)
    if (scaled !== this) scaled.recycle()
    return out
}
