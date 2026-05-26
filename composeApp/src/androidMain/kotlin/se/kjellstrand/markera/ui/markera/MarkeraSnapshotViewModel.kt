package se.kjellstrand.markera.ui.markera

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import se.kjellstrand.markera.vision.CameraIntrinsics

class MarkeraSnapshotViewModel : ViewModel() {
    var snapshot: Bitmap? by mutableStateOf(null)
        private set

    var intrinsics: CameraIntrinsics? by mutableStateOf(null)

    fun set(bitmap: Bitmap) {
        snapshot = bitmap
    }

    fun clear() {
        snapshot = null
    }
}
