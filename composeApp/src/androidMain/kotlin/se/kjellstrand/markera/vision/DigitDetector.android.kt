package se.kjellstrand.markera.vision

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android [DigitDetector] backed by ML Kit's on-device Latin text recogniser.
 * Only single characters '1'..'9' are kept; each element's [Text.Element.getBoundingBox]
 * is already in the input [Bitmap]'s pixel space, matching [Detection].
 */
actual class DigitDetector actual constructor() {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    actual suspend fun detect(image: Bitmap): List<DigitDetection> =
        suspendCancellableCoroutine { cont ->
            val input = InputImage.fromBitmap(image, 0)
            recognizer.process(input)
                .addOnSuccessListener { text -> cont.resume(extractDigits(text)) }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }

    actual fun close() {
        recognizer.close()
    }

    private fun extractDigits(text: Text): List<DigitDetection> {
        val out = mutableListOf<DigitDetection>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val value = element.text.singleDigitOrNull() ?: continue
                    val box = element.boundingBox ?: continue
                    out += DigitDetection(
                        left = box.left.toFloat(),
                        top = box.top.toFloat(),
                        right = box.right.toFloat(),
                        bottom = box.bottom.toFloat(),
                        value = value,
                        // ML Kit exposes no per-element confidence.
                        conf = 1f,
                    )
                }
            }
        }
        return out
    }
}

/** Parse a recognised string that is exactly one digit '1'..'9', else null. */
private fun String.singleDigitOrNull(): Int? {
    if (length != 1) return null
    val c = this[0]
    return if (c in '1'..'9') c - '0' else null
}
