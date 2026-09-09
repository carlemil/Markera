package se.kjellstrand.markera.ui.markera

import androidx.compose.ui.graphics.ImageBitmap
import se.kjellstrand.markera.vision.PlatformImage

/**
 * The frozen frame as something Compose can draw. Lives here, not next to the
 * other [PlatformImage] expects in `vision/`, because the `:eval` JVM module
 * compiles `vision/` and cannot see Compose.
 */
expect fun PlatformImage.toImageBitmap(): ImageBitmap
