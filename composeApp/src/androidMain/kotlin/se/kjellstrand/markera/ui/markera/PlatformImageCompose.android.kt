package se.kjellstrand.markera.ui.markera

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import se.kjellstrand.markera.vision.PlatformImage

actual fun PlatformImage.toImageBitmap(): ImageBitmap = asImageBitmap()
