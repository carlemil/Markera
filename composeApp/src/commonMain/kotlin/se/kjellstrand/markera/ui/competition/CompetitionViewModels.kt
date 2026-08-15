package se.kjellstrand.markera.ui.competition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import se.kjellstrand.markera.webshooter.ScoringRepository
import se.kjellstrand.markera.webshooter.api.WebshooterApiException
import se.kjellstrand.markera.webshooter.api.dto.CompetitionSummaryDto
import se.kjellstrand.markera.webshooter.api.dto.ScoringTargetsResponse
import se.kjellstrand.markera.webshooter.auth.SessionRepository

data class LoginUiState(
    val loading: Boolean = false,
    val failed: Boolean = false,
)

class LoginViewModel(
    private val sessionRepository: SessionRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    /** The screen observes sessionRepository.session to navigate on success. */
    fun login(email: String, password: String) {
        if (_uiState.value.loading) return
        _uiState.value = LoginUiState(loading = true)
        viewModelScope.launch {
            try {
                sessionRepository.login(email.trim(), password)
                _uiState.value = LoginUiState()
            } catch (e: Exception) {
                println("Markera: webshooter login failed: $e")
                _uiState.value = LoginUiState(failed = true)
            }
        }
    }
}

data class CompetitionListUiState(
    val loading: Boolean = true,
    val error: Boolean = false,
    val search: String = "",
    val competitions: List<CompetitionSummaryDto> = emptyList(),
)

class CompetitionListViewModel(
    private val scoringRepository: ScoringRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CompetitionListUiState())
    val uiState: StateFlow<CompetitionListUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun setSearch(search: String) {
        _uiState.update { it.copy(search = search) }
        load(debounceMs = 300)
    }

    fun load(debounceMs: Long = 0) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            _uiState.update { it.copy(loading = true, error = false) }
            try {
                val page = scoringRepository.competitions(search = _uiState.value.search)
                // Only competitions still open for marking — avslutade are hidden.
                val current = page.data.filterNot { it.isCompleted }
                _uiState.update { it.copy(loading = false, competitions = current) }
            } catch (_: Exception) {
                _uiState.update { it.copy(loading = false, error = true) }
            }
        }
    }
}

data class MarkingGroupsUiState(
    val loading: Boolean = true,
    val error: Boolean = false,
    /** 403 with error=no_active_patrol, or targets without an active patrol. */
    val notEnabled: Boolean = false,
    val targets: ScoringTargetsResponse? = null,
)

class MarkingGroupsViewModel(
    private val scoringRepository: ScoringRepository,
    private val competitionId: Int,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MarkingGroupsUiState())
    val uiState: StateFlow<MarkingGroupsUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = MarkingGroupsUiState(loading = true)
            try {
                val targets = scoringRepository.scoringTargets(competitionId)
                _uiState.value = MarkingGroupsUiState(loading = false, targets = targets)
            } catch (e: WebshooterApiException) {
                _uiState.value = MarkingGroupsUiState(
                    loading = false,
                    error = !e.isNoActivePatrol && e.status != 403,
                    notEnabled = e.isNoActivePatrol || e.status == 403,
                )
            } catch (_: Exception) {
                _uiState.value = MarkingGroupsUiState(loading = false, error = true)
            }
        }
    }
}
