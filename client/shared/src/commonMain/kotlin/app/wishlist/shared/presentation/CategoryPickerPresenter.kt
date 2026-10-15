package app.wishlist.shared.presentation

import app.wishlist.shared.core.AuthSession
import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.SessionSnapshot
import app.wishlist.shared.core.canonicalUuidOrNull
import app.wishlist.shared.model.*
import app.wishlist.shared.repository.CategoryRepository
import app.wishlist.shared.repository.LocalStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * FProductCategoryPicker: the whole SELECT taxonomy plus every custom, one rail page per group.
 * [catalog] is also handed to the in-sheet create form so it does not read SELECT again.
 * [selected] is the chip filled now; [result] is the pick to hand back (C8 puts it in the edit draft).
 */
data class CategoryPickerState(
    val catalog: CategoryCatalog?,
    val currentGroupId: String?,
    val selectedId: String?,
    val loading: Boolean,
    val error: ClientError?,
    val result: CategoryEntry?,
) {
    companion object {
        val Initial = CategoryPickerState(null, null, null, loading = false, error = null, result = null)
    }
}

/**
 * Intents: [open] (the sheet opens with the current selection), [showGroup], [select] (a chip: picks
 * and the platform closes the sheet), [onCreated] (the in-sheet form made one: D14 picks it — the
 * SELECT is read again for its entry, and the detail itself stands in if that fails), [retry],
 * [consumeResult]. A session change resets.
 */
class CategoryPickerPresenter internal constructor(
    private val categories: CategoryRepository,
    private val store: LocalStore,
    session: AuthSession,
    private val dispatcher: CoroutineDispatcher,
) : SessionLane(session, dispatcher) {
    private val mutableState = MutableStateFlow(CategoryPickerState.Initial)
    val state: StateFlow<CategoryPickerState> = mutableState.asStateFlow()

    private var job: Job? = null
    private var opened = false

    fun open(selectedId: String?) {
        intent {
            opened = true
            val selected = selectedId?.let { canonicalUuidOrNull(it) ?: it }
            mutableState.value = CategoryPickerState.Initial.copy(selectedId = selected, loading = true)
            fetch(created = null)
        }
    }

    fun retry() { intent { if (opened && mutableState.value.catalog == null) { mutableState.value = mutableState.value.copy(loading = true, error = null); fetch(null) } } }

    fun showGroup(groupId: String) {
        intent {
            val s = mutableState.value
            if (s.catalog?.groups?.any { it.id == groupId } == true) mutableState.value = s.copy(currentGroupId = groupId)
        }
    }

    fun select(id: String) {
        intent {
            val entry = mutableState.value.catalog?.groups?.flatMap { it.categories }?.firstOrNull { it.id == id } ?: return@intent
            mutableState.value = mutableState.value.copy(selectedId = entry.id, result = entry)
        }
    }

    fun onCreated(detail: CustomCategoryDetail) {
        intent {
            mutableState.value = mutableState.value.copy(loading = true)
            fetch(created = detail)
        }
    }

    fun consumeResult() { intent { mutableState.value = mutableState.value.copy(result = null) } }

    override fun onSessionChanged(current: SessionSnapshot) {
        job?.cancel(); job = null
        opened = false
        mutableState.value = CategoryPickerState.Initial
    }

    private suspend fun fetch(created: CustomCategoryDetail?) {
        val account = observed.accountId
        val snapshot = observed
        val saved = account?.let { (withContext(dispatcher) { store.readAppState(lastCategoryGroupKey(it)) } as? ClientResult.Success)?.value }
        if (observed != snapshot) return
        job?.cancel()
        job = request({ categories.catalog(CategoryScope.SELECT) }) { result ->
            job = null
            val s = mutableState.value
            when (result) {
                is ClientResult.Success -> {
                    val catalog = result.value
                    val entries = catalog.groups.flatMap { it.categories }
                    val pick = created?.let { d -> entries.firstOrNull { it.id == d.id } ?: d.asEntry() }
                    val selected = pick?.id ?: s.selectedId
                    val groupOfSelected = catalog.groups.firstOrNull { g -> g.categories.any { it.id == selected } }?.id
                    val current = listOf(created?.parentId, groupOfSelected, s.currentGroupId, saved)
                        .firstOrNull { id -> id != null && catalog.groups.any { it.id == id } } ?: catalog.groups.firstOrNull()?.id
                    mutableState.value = s.copy(catalog = catalog, currentGroupId = current, selectedId = selected,
                        loading = false, error = null, result = pick ?: s.result)
                }
                is ClientResult.Failure -> mutableState.value =
                    if (created != null) s.copy(loading = false, selectedId = created.id, result = created.asEntry())
                    else s.copy(loading = false, error = result.error)
            }
        }
    }

    private fun CustomCategoryDetail.asEntry() =
        CategoryEntry(id, name, parentId, CategoryKind.CUSTOM, "CUSTOM", displayOrder = -1, itemCount = itemCount)
}
