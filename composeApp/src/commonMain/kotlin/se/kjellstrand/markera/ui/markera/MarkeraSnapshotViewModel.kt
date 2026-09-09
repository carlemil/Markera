package se.kjellstrand.markera.ui.markera

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import se.kjellstrand.markera.vision.PlatformImage

class MarkeraSnapshotViewModel : ViewModel() {
    var snapshot: PlatformImage? by mutableStateOf(null)
        private set

    fun set(bitmap: PlatformImage) {
        snapshot = bitmap
    }

    fun clear() {
        snapshot = null
    }
}
