package se.kjellstrand.markera.series

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

fun interface ClaimGate {
    suspend fun needsDecision(auth: BackendAuth): Int
}

sealed interface ClaimState {
    data object Hidden : ClaimState
    data class Ask(val count: Int) : ClaimState
    data class ConfirmDelete(val count: Int) : ClaimState
    data object Working : ClaimState
    data class Error(val error: Throwable) : ClaimState
}

/** Compile-only claim contract for the shared test branches; #654 supplies behavior. */
class LocalClaimController(
    private val repository: SeriesRepository,
    private val session: BackendSessionRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ClaimState>(ClaimState.Hidden)
    val state: StateFlow<ClaimState> = _state.asStateFlow()
    fun add(): Unit = TODO("#654")
    fun askDelete(): Unit = TODO("#654")
    fun confirmDelete(): Unit = TODO("#654")
    fun cancelDelete(): Unit = TODO("#654")
    fun staySignedOut(): Unit = TODO("#654")
    fun dismiss(): Unit = TODO("#654")
    fun dispose(): Unit = TODO("#654")
}
