package se.kjellstrand.markera.vision

/**
 * On-device OCR detector for the printed digits on a precision-shooting
 * target. The Android actual is backed by ML Kit Text Recognition; the iOS
 * actual is a compile-only stub until an iOS host app exists and Apple Vision
 * can be wired in.
 *
 * Unlike [HoleDetector] (which needs a normalised CHW tensor), OCR runs on the
 * platform's native image, so [detect] takes a [PlatformImage]. The returned
 * boxes are already in that image's pixel space — the same space [Detection]
 * uses — so they need no letterbox inverse to overlay.
 */
expect class DigitDetector() {

    /**
     * Recognise single digit characters '1'..'9' on [image]. Returns one
     * [DigitDetection] per recognised digit, in image pixel space.
     *
     * Confidence: iOS reports Vision's per-candidate confidence. ML Kit has
     * none per element, so Android always reports `1f`, and a
     * [CentreConfig.minConf] filter can only ever drop iOS digits.
     */
    suspend fun detect(image: PlatformImage): List<DigitDetection>

    fun close()
}

/** The digit 1..9 this one-character OCR string reads as, else null. */
internal fun String.singleDigitOrNull(): Int? {
    if (length != 1) return null
    val c = this[0]
    return if (c in '1'..'9') c - '0' else null
}
