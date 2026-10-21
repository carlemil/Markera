package se.kjellstrand.markera.series

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

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

/** Owns the held-login decision. Synchronous state transitions exclude repeated taps. */
class LocalClaimController(
    private val repository: SeriesRepository,
    private val session: BackendSessionRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ClaimState>(ClaimState.Hidden)
    val state: StateFlow<ClaimState> = _state.asStateFlow()
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val choices = Mutex()
    init {
        scope.launch(job) {
            session.pendingAuth.collect { auth ->
                val next = if (auth == null) ClaimState.Hidden else ClaimState.Ask(repository.localCount(auth.userId))
                if (_state.value != ClaimState.Working && session.pendingAuth.value == auth) {
                    _state.value = next
                }
            }
        }
    }

    private fun perform(action: suspend () -> Unit) {
        if (_state.value == ClaimState.Working || _state.value == ClaimState.Hidden) return
        if (!choices.tryLock()) return
        _state.value = ClaimState.Working
        scope.launch(job) {
            try {
                action()
                _state.value = ClaimState.Hidden
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.value = ClaimState.Error(e) }
            finally { choices.unlock() }
        }
    }

    fun add() = perform {
        val pending = session.pendingAuth.value ?: return@perform
        // Claim before token persistence. Once auth is visible, refresh is free to run.
        repository.claimLocal(pending.userId)
        session.confirmPending()
        repository.refresh() // Partial failures remain this account's durable queue.
    }
    fun askDelete() {
        val ask = _state.value as? ClaimState.Ask ?: return
        _state.value = ClaimState.ConfirmDelete(ask.count)
    }
    fun confirmDelete() {
        if (_state.value !is ClaimState.ConfirmDelete) return
        perform {
            val pending = session.pendingAuth.value ?: return@perform
            repository.deleteOfferedLocal(pending.userId)
            session.confirmPending()
        }
    }
    fun cancelDelete() {
        val confirm = _state.value as? ClaimState.ConfirmDelete ?: return
        _state.value = ClaimState.Ask(confirm.count)
    }
    fun staySignedOut() = perform { session.discardPending() }
    fun dismiss() = staySignedOut()
    fun dispose() { job.cancel() }
}
