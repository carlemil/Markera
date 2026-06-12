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
     */
    suspend fun detect(image: PlatformImage): List<DigitDetection>

    fun close()
}
