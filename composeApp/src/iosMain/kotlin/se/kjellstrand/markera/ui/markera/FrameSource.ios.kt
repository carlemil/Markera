package se.kjellstrand.markera.ui.markera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import se.kjellstrand.markera.vision.PlatformImage

/** Placeholder until the iOS camera lands: a black viewport that captures nothing. */
private object StubFrameSource : FrameSource {
    override val requiresCameraPermission = false

    @Composable
    override fun Preview(onError: (Throwable) -> Unit, modifier: Modifier) {
        Box(modifier.background(Color.Black))
    }

    override suspend fun capture(): PlatformImage? = null

    override fun onResumeLive() = Unit
}

@Composable
actual fun rememberFrameSource(): FrameSource = StubFrameSource
