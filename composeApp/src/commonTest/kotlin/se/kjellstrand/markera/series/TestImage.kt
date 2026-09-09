package se.kjellstrand.markera.series

import se.kjellstrand.markera.vision.PlatformImage

/**
 * A stand-in scanned frame. The recorder only ever hands it to `encodeJpeg`,
 * and the test encoders ignore it, so it needs no pixels.
 */
expect fun testImage(): PlatformImage
