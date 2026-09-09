package se.kjellstrand.markera.ui.markera

import se.kjellstrand.markera.vision.PlatformImage
import se.kjellstrand.markera.vision.argb
import se.kjellstrand.markera.vision.height
import se.kjellstrand.markera.vision.letterboxChw
import se.kjellstrand.markera.vision.letterboxDims
import se.kjellstrand.markera.vision.lumaFromArgb
import se.kjellstrand.markera.vision.width

/** The frame as row-major 8-bit luma, at its own resolution. */
fun PlatformImage.toGrayscale(): ByteArray = lumaFromArgb(argb(width, height))

/** The frame letterboxed into the detector's square CHW input tensor. */
fun PlatformImage.toModelInput(inputSize: Int): FloatArray {
    val (w, h) = letterboxDims(width, height, inputSize)
    return letterboxChw(argb(w, h), w, h, inputSize)
}
