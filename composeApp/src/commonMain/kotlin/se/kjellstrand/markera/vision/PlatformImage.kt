package se.kjellstrand.markera.vision

/**
 * The platform's native image type handed to [DigitDetector] and to the
 * pixel helpers below. Android backs this with `android.graphics.Bitmap`;
 * iOS with a `UIImage`. Keeping it an `expect class` lets the common
 * [DigitDetector] surface stay platform-neutral while the OCR engines consume
 * their native image directly (no copy through a common buffer, unlike
 * [HoleDetector]'s CHW tensor).
 */
expect class PlatformImage

expect val PlatformImage.width: Int

expect val PlatformImage.height: Int

/**
 * Centre-crop to a square (side = the shorter edge). Matches the FILL_CENTER
 * live preview so the frozen frame, detections, and centre all operate on the
 * same square the user framed. Returns the receiver if already square.
 */
expect fun PlatformImage.centerSquare(): PlatformImage

/**
 * The one pixel primitive: row-major `0xAARRGGBB` pixels, smooth-scaled to
 * [width] x [height] when that differs from the image's own size. Everything
 * else (luma, the model letterbox) is pure Kotlin on top of this, in
 * `ImageOps.kt`.
 */
expect fun PlatformImage.argb(width: Int, height: Int): IntArray
