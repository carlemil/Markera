@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package se.kjellstrand.markera.ui.markera

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.useContents
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.compose.resources.stringResource
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCapturePhoto
import platform.AVFoundation.AVCapturePhotoCaptureDelegateProtocol
import platform.AVFoundation.AVCapturePhotoOutput
import platform.AVFoundation.AVCapturePhotoSettings
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPresetPhoto
import platform.AVFoundation.AVCaptureVideoOrientationPortrait
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.fileDataRepresentation
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSError
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageOrientation
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.ios_pick_photo
import se.kjellstrand.markera.series.keyWindow
import se.kjellstrand.markera.vision.PlatformImage
import kotlin.coroutines.resume

/**
 * The simulator (and any device without a camera) scores a picked photo: the
 * chosen image *is* the frame the pipeline scores.
 */
private class PhotoPickerFrameSource : FrameSource {
    private var image: UIImage? by mutableStateOf<UIImage?>(null)

    override val requiresCameraPermission = false

    @Composable
    override fun Preview(onError: (Throwable) -> Unit, modifier: Modifier) {
        val shown = image
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            if (shown == null) {
                Button(onClick = { presentPicker { image = it } }) {
                    Text(stringResource(Res.string.ios_pick_photo))
                }
            } else {
                PickedPhoto(shown)
                PickButton { image = it }
            }
        }
    }

    override suspend fun capture(): PlatformImage? = image

    override fun onResumeLive() {
        image = null
    }
}

/**
 * The device [FrameSource]: a live AVFoundation preview, with a full-resolution
 * still from [AVCapturePhotoOutput] as the scanned frame. A photo picked from
 * the library overrides the live frame until the next "resume live".
 *
 * Configuration failures throw from the constructor, and [rememberFrameSource]
 * then falls back to the picker — there is no half-working camera state.
 */
private class CameraFrameSource : FrameSource {
    override val requiresCameraPermission = true

    private var picked: UIImage? by mutableStateOf<UIImage?>(null)

    private val session = AVCaptureSession()
    private val photoOutput = AVCapturePhotoOutput()

    // AVFoundation holds the capture delegate weakly; park it for the shot.
    private var captureDelegate: PhotoCaptureDelegate? = null

    init {
        val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
            ?: error("No video capture device")
        val input = AVCaptureDeviceInput.deviceInputWithDevice(device, error = null)
            ?: error("Cannot open the camera")
        session.beginConfiguration()
        session.sessionPreset = AVCaptureSessionPresetPhoto
        check(session.canAddInput(input)) { "Cannot add the camera input" }
        session.addInput(input)
        check(session.canAddOutput(photoOutput)) { "Cannot add the photo output" }
        session.addOutput(photoOutput)
        session.commitConfiguration()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    override fun Preview(onError: (Throwable) -> Unit, modifier: Modifier) {
        val shown = picked
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            if (shown == null) {
                UIKitView(
                    factory = { CameraPreviewView(session) },
                    modifier = Modifier.fillMaxSize(),
                )
                DisposableEffect(Unit) {
                    // startRunning blocks; AVFoundation wants it off the main thread.
                    onBackgroundQueue { session.startRunning() }
                    onDispose { onBackgroundQueue { session.stopRunning() } }
                }
            } else {
                PickedPhoto(shown)
            }
            PickButton { picked = it }
        }
    }

    override suspend fun capture(): PlatformImage? {
        picked?.let { return it }
        return suspendCancellableCoroutine { cont ->
            val delegate = PhotoCaptureDelegate { image -> cont.resume(image) }
            captureDelegate = delegate
            photoOutput.capturePhotoWithSettings(AVCapturePhotoSettings.photoSettings(), delegate)
        }
    }

    override fun onResumeLive() {
        picked = null
    }
}

/** A plain view whose only job is to keep the preview layer at its bounds. */
@Suppress("DEPRECATION") // videoOrientation, deprecated in iOS 17; the target is 16.
private class CameraPreviewView(session: AVCaptureSession) :
    UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {

    private val previewLayer = AVCaptureVideoPreviewLayer(session = session)

    init {
        // Fill-centre, the same square framing as the Android PreviewView: the
        // shared centerSquare() then matches what the viewport shows.
        previewLayer.videoGravity = AVLayerVideoGravityResizeAspectFill
        layer.addSublayer(previewLayer)
        // Portrait-only app; the connection defaults to landscape-right.
        previewLayer.connection?.let { connection ->
            if (connection.isVideoOrientationSupported()) {
                connection.videoOrientation = AVCaptureVideoOrientationPortrait
            }
        }
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        previewLayer.frame = bounds
    }
}

private class PhotoCaptureDelegate(
    private val onPhoto: (UIImage?) -> Unit,
) : NSObject(), AVCapturePhotoCaptureDelegateProtocol {

    override fun captureOutput(
        output: AVCapturePhotoOutput,
        didFinishProcessingPhoto: AVCapturePhoto,
        error: NSError?,
    ) {
        if (error != null) println("CameraFrameSource: capture failed: ${error.localizedDescription}")
        // The JPEG carries an EXIF orientation; upright() bakes it into pixels.
        onPhoto(didFinishProcessingPhoto.fileDataRepresentation()?.let { UIImage(data = it) }?.upright())
    }
}

@Composable
private fun PickedPhoto(image: UIImage) {
    // A picked photo can be 12 MP; only re-raster when it changes.
    val bitmap = remember(image) { image.toImageBitmap() }
    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
}

/** Swap the frame for a photo from the library. */
@Composable
private fun BoxScope.PickButton(onPicked: (UIImage?) -> Unit) {
    IconButton(
        onClick = { presentPicker(onPicked) },
        modifier = Modifier.align(Alignment.TopEnd),
    ) {
        Icon(
            imageVector = Icons.Default.PhotoLibrary,
            contentDescription = stringResource(Res.string.ios_pick_photo),
        )
    }
}

private fun onBackgroundQueue(block: () -> Unit) =
    dispatch_async(dispatch_get_global_queue(0.convert(), 0.convert()), block)

// PHPickerViewController holds its delegate weakly; park it until it fires.
private var pickerDelegate: PickerDelegate? = null

private fun presentPicker(onPicked: (UIImage?) -> Unit) {
    val config = PHPickerConfiguration().apply {
        filter = PHPickerFilter.imagesFilter
        selectionLimit = 1L
    }
    val picker = PHPickerViewController(configuration = config)
    val delegate = PickerDelegate(onPicked)
    pickerDelegate = delegate
    picker.delegate = delegate
    keyWindow().rootViewController
        ?.presentViewController(picker, animated = true, completion = null)
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
actual fun rememberFrameSource(): FrameSource = remember {
    // No capture device on the simulator, and a camera that will not configure
    // is no better than none: both fall back to picking a photo.
    if (AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo) == null) {
        PhotoPickerFrameSource()
    } else {
        try {
            CameraFrameSource()
        } catch (t: Throwable) {
            println("CameraFrameSource unavailable, picking photos instead: $t")
            PhotoPickerFrameSource()
        }
    }
}
