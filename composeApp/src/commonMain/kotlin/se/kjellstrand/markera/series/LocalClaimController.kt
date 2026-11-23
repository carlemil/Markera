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
    /**
     * Counts the local rows a sign-in as [auth] must decide on. With none, runs [publish]
     * before letting go of the local store, so no save lands unowned between the count and
     * the publication (#25).
     */
    suspend fun needsDecision(auth: BackendAuth, publish: suspend () -> Unit): Int
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

    /**
     * The rows the shown count stands for. Add and Delete act on exactly these (#26); one saved
     * after they were counted keeps the login held and gets its own question.
     */
    private var offered: Set<Long> = emptySet()

    init {
        scope.launch(job) {
            session.pendingAuth.collect { auth ->
                val ids = if (auth == null) emptyList() else repository.offeredLocal(auth.userId)
                if (_state.value != ClaimState.Working && session.pendingAuth.value == auth) {
                    offered = ids.toSet()
                    _state.value = if (auth == null) ClaimState.Hidden else ClaimState.Ask(ids.size)
                }
            }
        }
    }

    /** After acting on [offered]: publish, or, when rows arrived meanwhile, ask about those. */
    private suspend fun publishOrAskAgain(pending: BackendAuth): ClaimState {
        if (session.confirmPending() != null || session.pendingAuth.value != pending) return ClaimState.Hidden
        val ids = repository.offeredLocal(pending.userId)
        // Gone again before this recount (deleted from History): nothing left to ask about.
        if (ids.isEmpty()) return publishOrAskAgain(pending)
        offered = ids.toSet()
        return ClaimState.Ask(ids.size)
    }

    /** [action] returns the state to show once it is done. */
    private fun perform(action: suspend () -> ClaimState) {
        if (_state.value == ClaimState.Working || _state.value == ClaimState.Hidden) return
        if (!choices.tryLock()) return
        _state.value = ClaimState.Working
        scope.launch(job) {
            try {
                _state.value = action()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.value = ClaimState.Error(e) }
            finally { choices.unlock() }
        }
    }

    fun add() = perform {
        val pending = session.pendingAuth.value ?: return@perform ClaimState.Hidden
        // Claim before token persistence. Once auth is visible, refresh is free to run.
        repository.claimLocal(pending.userId, offered)
        val next = publishOrAskAgain(pending)
        if (next == ClaimState.Hidden && session.auth.value == pending) {
            repository.refresh() // Partial failures remain this account's durable queue.
        }
        next
    }
    fun askDelete() {
        val ask = _state.value as? ClaimState.Ask ?: return
        _state.value = ClaimState.ConfirmDelete(ask.count)
    }
    fun confirmDelete() {
        if (_state.value !is ClaimState.ConfirmDelete) return
        perform {
            val pending = session.pendingAuth.value ?: return@perform ClaimState.Hidden
            repository.deleteOfferedLocal(pending.userId, offered)
            publishOrAskAgain(pending)
        }
    }
    fun cancelDelete() {
        val confirm = _state.value as? ClaimState.ConfirmDelete ?: return
        _state.value = ClaimState.Ask(confirm.count)
    }
    fun staySignedOut() = perform { session.discardPending(); ClaimState.Hidden }
    fun dismiss() = staySignedOut()
    fun dispose() { job.cancel() }
}
