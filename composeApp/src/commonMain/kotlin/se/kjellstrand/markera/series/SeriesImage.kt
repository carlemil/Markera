package se.kjellstrand.markera.series

import androidx.compose.ui.graphics.ImageBitmap
import se.kjellstrand.markera.vision.PlatformImage

/**
 * The scanned frame as a JPEG for the backend. These images are training data,
 * so they go up close to what the sensor gave: the size cap only guards against
 * a bigger sensor than today's ~3000 px square capture. The reported size is
 * the *source* one the hole coordinates are in.
 */
expect suspend fun encodeSeriesJpeg(image: PlatformImage): EncodedImage

/**
 * Decode a stored series JPEG down to at most [maxDim] px on the longer side.
 * The full frame is ~3000² (36 MB as ARGB), far more than any screen needs, so
 * every display path subsamples. Hole markers are placed from the series'
 * stored `imageWidth`/`imageHeight`, so the decoded size does not matter.
 */
expect fun decodeSeriesJpeg(bytes: ByteArray, maxDim: Int): ImageBitmap?
