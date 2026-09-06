@file:OptIn(ExperimentalForeignApi::class)

package se.kjellstrand.markera.vision

import kotlinx.cinterop.ExperimentalForeignApi

// The pointed-at struct, not `CGImageRef`: an `actual typealias` must resolve to
// a class, and CGImageRef is itself a typealias for a nullable CPointer.
actual typealias PlatformImage = cnames.structs.CGImage
