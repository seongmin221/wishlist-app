package app.wishlist.shared.presentation

import app.wishlist.shared.core.*
import app.wishlist.shared.model.*
import app.wishlist.shared.repository.PurposeRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class PurposeHomeState(
    val signedIn: Boolean = false, val loaded: Boolean = false,
    val purposes: List<PurposeSummary> = emptyList(), val activeCount: Int = 0,
    val purposeLimit: Int = 30, val archiveCount: Int = 0, val loading: Boolean = false,
    val error: ClientError? = null, val refreshFailed: Boolean = false,
)
class PurposeHomePresenter internal constructor(
    private val purposes: PurposeRepository, session: AuthSession, dispatcher: CoroutineDispatcher,
) : SessionLane(session, dispatcher) {
    private val mutable = MutableStateFlow(PurposeHomeState(signedIn = session.state.value.accountId != null))
    val state: StateFlow<PurposeHomeState> = mutable.asStateFlow()
    private var started = false
    private var job: Job? = null

    fun load() { intent { started = true; fetch() } }
    fun refresh() { intent { if (started) fetch() } }
    suspend fun refreshNow() {
        var pending: Job? = null
        intent { if (started) { fetch(); pending = job } }.join()
        pending?.join()
    }
    fun retry() = refresh()

    override fun onSessionChanged(current: SessionSnapshot) {
        job?.cancel(); job = null
        mutable.value = PurposeHomeState(signedIn = current.accountId != null)
        if (started && current.accountId != null) fetch()
    }

    private fun fetch() {
        if (observed.accountId == null) {
            mutable.value = PurposeHomeState(loaded = true)
            return
        }
        job?.cancel()
        mutable.value = mutable.value.copy(signedIn = true, loading = true, error = null, refreshFailed = false)
        job = request({ purposes.summary() }) { result ->
            job = null
            mutable.value = when (result) {
                is ClientResult.Success -> PurposeHomeState(
                    signedIn = true, loaded = true, purposes = result.value.purposes,
                    activeCount = result.value.activeCount, purposeLimit = result.value.purposeLimit,
                    archiveCount = result.value.archiveSummary.count,
                )
                is ClientResult.Failure -> if (mutable.value.loaded)
                    mutable.value.copy(loading = false, refreshFailed = true)
                else mutable.value.copy(loading = false, error = result.error)
            }
        }
    }
}
