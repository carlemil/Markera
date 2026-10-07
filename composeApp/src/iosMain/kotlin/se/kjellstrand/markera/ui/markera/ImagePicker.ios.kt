@file:OptIn(ExperimentalForeignApi::class)

package se.kjellstrand.markera.ui.markera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlin.coroutines.resume
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreGraphics.CGImageRelease
import platform.Foundation.NSData
import platform.Foundation.NSDictionary
import platform.Foundation.NSItemProvider
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.ImageIO.CGImageSourceCopyPropertiesAtIndex
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.kCGImageSourceCreateThumbnailFromImageAlways
import platform.ImageIO.kCGImageSourceCreateThumbnailWithTransform
import platform.ImageIO.kCGImageSourceThumbnailMaxPixelSize
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIImage
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.darwin.NSObject
import se.kjellstrand.markera.series.exifInstant
import se.kjellstrand.markera.series.keyWindow

/** The longer side an import is scaled down to: about a camera still. */
private const val IMPORT_MAX_SIDE = 4096

@Composable
actual fun rememberImagePicker(onPicked: (List<PickedImage>) -> Unit): (ImageSource) -> Unit {
    val callback = rememberUpdatedState(onPicked)
    return remember { { source -> presentImport(source) { if (it.isNotEmpty()) callback.value(it) } } }
}

// Both pickers hold their delegate weakly; park the open one's until it fires.
private var importDelegate: NSObject? = null

private fun presentImport(source: ImageSource, onPicked: (List<PickedImage>) -> Unit) {
    val controller: UIViewController = when (source) {
        ImageSource.GALLERY -> {
            val config = PHPickerConfiguration().apply {
                filter = PHPickerFilter.imagesFilter
                selectionLimit = 0L // No limit.
            }
            PHPickerViewController(configuration = config).also {
                val delegate = GalleryDelegate(onPicked)
                importDelegate = delegate
                it.delegate = delegate
            }
        }
        // Files, iCloud Drive and any file provider; copied into the app's tmp, so readable later.
        ImageSource.FILES -> UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeImage), asCopy = true).also {
            it.allowsMultipleSelection = true
            val delegate = FilesDelegate(onPicked)
            importDelegate = delegate
            it.delegate = delegate
        }
    }
    keyWindow().rootViewController?.presentViewController(controller, animated = true, completion = null)
}

private class GalleryDelegate(
    private val onPicked: (List<PickedImage>) -> Unit,
) : NSObject(), PHPickerViewControllerDelegateProtocol {

    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, null)
        importDelegate = null
        // The providers stay valid after the picker is gone: each is read on its turn.
        val providers = didFinishPicking.mapNotNull { (it as? PHPickerResult)?.itemProvider }
        onPicked(providers.map { provider -> PickedImage { provider.imageData()?.let(::decodeImport) } })
    }
}

private class FilesDelegate(
    private val onPicked: (List<PickedImage>) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {

    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        importDelegate = null
        val urls = didPickDocumentsAtURLs.mapNotNull { it as? NSURL }
        onPicked(urls.map { url -> PickedImage { NSData.dataWithContentsOfURL(url)?.let(::decodeImport) } })
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        importDelegate = null
    }
}

/** The raw bytes (JPEG, PNG, HEIC): the class-typed loadObjectOfClass does not map through cinterop. */
private suspend fun NSItemProvider.imageData(): NSData? = suspendCancellableCoroutine { cont ->
    if (!hasItemConformingToTypeIdentifier("public.image")) {
        cont.resume(null)
    } else {
        loadDataRepresentationForTypeIdentifier("public.image") { data, _ -> cont.resume(data) }
    }
}

/**
 * Decodes [data] upright (the transform bakes the EXIF orientation into the
 * pixels, as `upright()` does for the camera) and no longer than [IMPORT_MAX_SIDE],
 * with its EXIF date taken.
 */
private fun decodeImport(data: NSData): LoadedImage? {
    @Suppress("UNCHECKED_CAST")
    val cfData = CFBridgingRetain(data) as CFDataRef?
    val source = CGImageSourceCreateWithData(cfData, null)
    CFRelease(cfData)
    if (source == null) return null
    try {
        val properties = CFBridgingRelease(CGImageSourceCopyPropertiesAtIndex(source, 0uL, null)) as? NSDictionary
        val exif = properties?.objectForKey("{Exif}") as? NSDictionary
        val takenAt = exifInstant(
            exif?.objectForKey("DateTimeOriginal") as? String,
            exif?.objectForKey("OffsetTimeOriginal") as? String,
        )
        val options = CFDictionaryCreateMutable(null, 3, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        CFDictionarySetValue(options, kCGImageSourceCreateThumbnailFromImageAlways, kCFBooleanTrue)
        CFDictionarySetValue(options, kCGImageSourceCreateThumbnailWithTransform, kCFBooleanTrue)
        val maxSide = memScoped {
            CFNumberCreate(null, kCFNumberIntType, alloc<IntVar>().apply { value = IMPORT_MAX_SIDE }.ptr)
        }
        CFDictionarySetValue(options, kCGImageSourceThumbnailMaxPixelSize, maxSide)
        CFRelease(maxSide)
        val cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0uL, options)
        CFRelease(options)
        if (cgImage == null) return null
        val image = UIImage.imageWithCGImage(cgImage)
        CGImageRelease(cgImage)
        return LoadedImage(image, takenAt)
    } finally {
        CFRelease(source)
    }
}
