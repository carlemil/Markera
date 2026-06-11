package se.kjellstrand.markera.vision

/**
 * The platform's native image type handed to [DigitDetector]. Android backs
 * this with `android.graphics.Bitmap`; iOS with a `CGImageRef`. Keeping it an
 * `expect class` lets the common [DigitDetector] surface stay platform-neutral
 * while the OCR engines consume their native image directly (no copy through a
 * common buffer, unlike [HoleDetector]'s CHW tensor).
 */
expect class PlatformImage
