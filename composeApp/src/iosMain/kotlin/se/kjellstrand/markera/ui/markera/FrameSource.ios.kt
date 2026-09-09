@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package se.kjellstrand.markera.ui.markera

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import org.jetbrains.compose.resources.stringResource
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageOrientation
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.ios_pick_photo
import se.kjellstrand.markera.series.keyWindow
import se.kjellstrand.markera.vision.PlatformImage

/**
 * iOS stands in for the camera with a photo pick: the chosen image *is* the
 * frame the pipeline scores.
 */
private class PhotoPickerFrameSource : FrameSource {
    var image: UIImage? by mutableStateOf<UIImage?>(null)

    override val requiresCameraPermission = false

    // PHPickerViewController holds its delegate weakly; park it here so ARC
    // keeps it alive until the pick completes.
    private var delegate: PickerDelegate? = null

    @Composable
    override fun Preview(onError: (Throwable) -> Unit, modifier: Modifier) {
        val shown = image
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            if (shown == null) {
                Button(onClick = ::pick) { Text(stringResource(Res.string.ios_pick_photo)) }
            } else {
                // A picked photo can be 12 MP; only re-raster when it changes.
                val bitmap = remember(shown) { shown.toImageBitmap() }
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                IconButton(onClick = ::pick, modifier = Modifier.align(Alignment.TopEnd)) {
                    Icon(
                        imageVector = Icons.Default.PhotoLibrary,
                        contentDescription = stringResource(Res.string.ios_pick_photo),
                    )
                }
            }
        }
    }

    override suspend fun capture(): PlatformImage? = image

    override fun onResumeLive() {
        image = null
    }

    private fun pick() {
        val config = PHPickerConfiguration().apply {
            filter = PHPickerFilter.imagesFilter
            selectionLimit = 1L
        }
        val picker = PHPickerViewController(configuration = config)
        val d = PickerDelegate { picked -> image = picked }
        delegate = d
        picker.delegate = d
        keyWindow().rootViewController
            ?.presentViewController(picker, animated = true, completion = null)
    }
}

private class PickerDelegate(
    private val onPicked: (UIImage?) -> Unit,
) : NSObject(), PHPickerViewControllerDelegateProtocol {

    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, null)
        val provider = (didFinishPicking.firstOrNull() as? PHPickerResult)?.itemProvider ?: return
        // The class-typed loadObjectOfClass(UIImage) does not map through
        // cinterop; the raw image bytes do, and UIImage(data:) reads every
        // format the picker hands out (JPEG, PNG, HEIC).
        if (!provider.hasItemConformingToTypeIdentifier("public.image")) return
        provider.loadDataRepresentationForTypeIdentifier("public.image") { data, _ ->
            // The completion runs on a background queue; UIImage drawing and
            // Compose state both want the main thread.
            dispatch_async(dispatch_get_main_queue()) {
                onPicked(data?.let { UIImage(data = it) }?.upright())
            }
        }
    }
}

/**
 * Redraws the image so its pixels are upright — everything downstream
 * (`centerSquare`, `argb`) reads the CGImage directly and ignores
 * `imageOrientation`.
 */
private fun UIImage.upright(): UIImage {
    if (imageOrientation == UIImageOrientation.UIImageOrientationUp) return this
    // size is points; with the renderer at scale 1 the output pixel size is
    // exactly the size we hand it, so scale the points up first.
    val w = size.useContents { width } * scale
    val h = size.useContents { height } * scale
    val format = UIGraphicsImageRendererFormat.defaultFormat()
    format.scale = 1.0
    return UIGraphicsImageRenderer(size = CGSizeMake(w, h), format = format)
        .imageWithActions { drawInRect(CGRectMake(0.0, 0.0, w, h)) }
}

@Composable
actual fun rememberFrameSource(): FrameSource = remember { PhotoPickerFrameSource() }
