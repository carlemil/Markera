package se.kjellstrand.markera.ui.markera

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tracks whether the camera's use cases (and so the [androidx.camera.core.ImageCapture]) are bound,
 * across the asynchronous CameraX bind. Pure, so it is JVM-tested.
 *
 * Each time the preview enters composition it [open]s a [Session]; the provider listener asks
 * [Session.mayBind] before binding (a preview disposed before the provider was ready must not bind
 * afterwards and keep the camera on, #12) and reports [Session.bound]. [Session.dispose] clears the
 * bound flag, but only for the newest session, so a late dispose of an old preview cannot hide a
 * newer bind. [awaitBound] lets a capture wait for the rebind after a frozen frame instead of
 * falling back to the low-resolution screen bitmap (#20).
 *
 * All calls but [awaitBound] come from the main thread.
 */
class CameraBindGate {
    private val _bound = MutableStateFlow(false)
    val bound: StateFlow<Boolean> = _bound.asStateFlow()

    private var current: Session? = null

    fun open(): Session = Session().also {
        current = it
        _bound.value = false
    }

    /** True once bound, false if [timeoutMillis] passes first. */
    suspend fun awaitBound(timeoutMillis: Long): Boolean =
        withTimeoutOrNull(timeoutMillis) { _bound.first { it } } ?: false

    inner class Session internal constructor() {
        var disposed = false
            private set

        /** Whether the provider listener may still bind for this session. */
        fun mayBind(): Boolean = !disposed && current === this

        /** The use cases are bound for this session. Ignored once disposed or superseded. */
        fun bound() {
            if (mayBind()) _bound.value = true
        }

        /**
         * Ends the session. Returns true when it was the newest one, i.e. the caller should unbind
         * the camera; a superseded session leaves that to the newer one's bind.
         */
        fun dispose(): Boolean {
            if (disposed) return false
            disposed = true
            if (current !== this) return false
            current = null
            _bound.value = false
            return true
        }
    }
}
