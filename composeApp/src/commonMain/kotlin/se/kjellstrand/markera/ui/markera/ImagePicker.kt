package se.kjellstrand.markera.ui.markera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import se.kjellstrand.markera.vision.PlatformImage

/** Where imported images come from: the photo library, or any file provider. */
enum class ImageSource { GALLERY, FILES }

/** A picked image, read only on its turn so an import holds one in memory at a time. */
fun interface PickedImage {
    /** Decoded upright, a huge photo scaled down to about camera-still size; null when unreadable. */
    suspend fun load(): LoadedImage?
}

/** [takenAt]: the photo's EXIF date taken as an ISO instant (see `exifInstant`), if it has one. */
class LoadedImage(val image: PlatformImage, val takenAt: String?)

/** Returns a launcher: opens the system picker for that source, multi-select, images only. */
@Composable
expect fun rememberImagePicker(onPicked: (List<PickedImage>) -> Unit): (ImageSource) -> Unit

/** The import queue's frame: [capture] hands the pipeline the image on its turn. */
class ImportFrameSource : FrameSource {
    var image: PlatformImage? by mutableStateOf(null)

    override val requiresCameraPermission = false

    @Composable
    override fun Preview(onError: (Throwable) -> Unit, modifier: Modifier) {
        Box(modifier.background(Color.Black))
    }

    override suspend fun capture(): PlatformImage? = image

    override fun onResumeLive() {
        image = null
    }
}
