package se.kjellstrand.markera.series

import se.kjellstrand.markera.vision.PlatformImage
import sun.misc.Unsafe

// PlatformImage is android.graphics.Bitmap, which a JVM unit test cannot
// construct (every method of the mockable android.jar throws, and there is
// no Robolectric here). An uninitialised instance is enough: the recorder
// only ever hands it to encodeJpeg, and the encoders ignore it.
actual fun testImage(): PlatformImage {
    val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
    return (field.get(null) as Unsafe).allocateInstance(PlatformImage::class.java) as PlatformImage
}
