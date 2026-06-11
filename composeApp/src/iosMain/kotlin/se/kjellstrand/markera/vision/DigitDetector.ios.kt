package se.kjellstrand.markera.vision

// TODO: swap this stub for an Apple Vision-backed implementation once an iOS
// host app exists. Use VNRecognizeTextRequest (recognitionLevel = Accurate,
// usesLanguageCorrection = false) via a VNImageRequestHandler(cgImage:), keep
// single chars '1'..'9', and convert Vision's normalised bottom-left boxes to
// top-left pixel space:
//   left = minX*W, right = maxX*W, top = (1-maxY)*H, bottom = (1-minY)*H
// using recognizedText.boundingBoxForRange for per-character boxes. Vision is a
// Kotlin/Native platform lib, so no Gradle/cinterop/CocoaPods is needed.
actual class DigitDetector actual constructor() {

    actual suspend fun detect(image: PlatformImage): List<DigitDetection> = emptyList()

    actual fun close() {}
}
