@file:OptIn(ExperimentalForeignApi::class)

package se.kjellstrand.markera.vision

import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRect
import platform.Foundation.NSMakeRange
import platform.Vision.VNImageRequestHandler
import platform.Vision.VNRecognizeTextRequest
import platform.Vision.VNRecognizedText
import platform.Vision.VNRecognizedTextObservation
import platform.Vision.VNRequestTextRecognitionLevelAccurate
import kotlin.time.TimeSource

/**
 * iOS [DigitDetector] backed by Apple Vision's text recogniser. Only single
 * characters '1'..'9' are kept (same rule as the Android/ML Kit actual) and the
 * normalised bottom-left boxes are converted to the image's top-left pixel
 * space, which is what [DigitDetection] and [Detection] share.
 */
actual class DigitDetector actual constructor() {

    actual suspend fun detect(image: PlatformImage): List<DigitDetection> =
        withContext(Dispatchers.Default) {
            // A CIImage-backed UIImage has no CGImage; Vision needs one.
            val cg = image.CGImage ?: return@withContext emptyList<DigitDetection>()
            val w = image.width.toDouble()
            val h = image.height.toDouble()
            val started = TimeSource.Monotonic.markNow()

            val request = VNRecognizeTextRequest()
            request.recognitionLevel = VNRequestTextRecognitionLevelAccurate
            request.usesLanguageCorrection = false
            // The synchronous form: performRequests blocks until done, and we
            // are already off the main thread.
            VNImageRequestHandler(cGImage = cg, options = mapOf<Any?, Any>())
                .performRequests(listOf(request), null)

            val observations = request.results?.filterIsInstance<VNRecognizedTextObservation>()
                ?: emptyList()
            val out = mutableListOf<DigitDetection>()
            for (obs in observations) {
                val candidate = obs.topCandidates(1.convert())
                    .filterIsInstance<VNRecognizedText>()
                    .firstOrNull() ?: continue
                val text = candidate.string
                val conf = candidate.confidence.toFloat()
                for ((start, token) in text.tokens()) {
                    val digit = token.singleDigitOrNull() ?: continue
                    val box = candidate
                        .boundingBoxForRange(NSMakeRange(start.convert(), 1.convert()), null)
                        ?.boundingBox
                    // Vision refuses per-character boxes for some candidates;
                    // the observation's own box only means this digit when the
                    // digit is all the observation says.
                        ?: if (text.trim() == token) obs.boundingBox else continue
                    out += box.toDigitDetection(w, h, digit, conf)
                }
            }
            println(
                "DigitDetector: ${observations.size} observations, " +
                    "${out.size} digits in ${started.elapsedNow()}",
            )
            out
        }

    actual fun close() {}
}

/** Whitespace-separated tokens with their start index in the string. */
private fun String.tokens(): List<Pair<Int, String>> {
    val out = mutableListOf<Pair<Int, String>>()
    var i = 0
    while (i < length) {
        if (this[i].isWhitespace()) {
            i++
            continue
        }
        var j = i
        while (j < length && !this[j].isWhitespace()) j++
        out += i to substring(i, j)
        i = j
    }
    return out
}

/** Parse a recognised string that is exactly one digit '1'..'9', else null. */
private fun String.singleDigitOrNull(): Int? {
    if (length != 1) return null
    val c = this[0]
    return if (c in '1'..'9') c - '0' else null
}

/** Vision's normalised, bottom-left rect to top-left pixel space. */
private fun CValue<CGRect>.toDigitDetection(w: Double, h: Double, digit: Int, conf: Float) =
    useContents {
        DigitDetection(
            left = (origin.x * w).toFloat(),
            top = ((1.0 - (origin.y + size.height)) * h).toFloat(),
            right = ((origin.x + size.width) * w).toFloat(),
            bottom = ((1.0 - origin.y) * h).toFloat(),
            value = digit,
            conf = conf,
        )
    }
