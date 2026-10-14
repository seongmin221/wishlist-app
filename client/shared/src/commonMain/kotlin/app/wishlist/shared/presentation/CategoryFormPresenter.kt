@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.wishlist.shared.presentation

import app.wishlist.shared.core.AuthSession
import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.core.IdGenerator
import app.wishlist.shared.core.SessionSnapshot
import app.wishlist.shared.domain.CategoryInputRules
import app.wishlist.shared.model.*
import app.wishlist.shared.repository.CategoryRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.uuid.Uuid

enum class FormMode { CREATE, EDIT }

/** Where a create form was opened: the tab's `+ 추가` sheet or the picker's `+ 카테고리` page. */
enum class FormSource { TAB, PICKER }

/**
 * LOADING: the SELECT (create) or CAT-02 (edit) baseline is on its way. LOAD_FAILED: it failed (retry).
 * CONFLICT_UNRESOLVED: a save hit a version conflict and the latest detail could not be read; the
 * draft stays and saving is off until [CategoryFormPresenter.retryLoad] reads it.
 */
enum class FormPhase { LOADING, READY, LOAD_FAILED, CONFLICT_UNRESOLVED }

/** An input field's error. Field keys are the server's (`parentId`, `name`, `description`, `examples`). */
enum class FieldError { DUPLICATE, INVALID_CHARS, PARENT }

/** A form-level line. */
enum class FormMessage { LIMIT_REACHED, RATE_LIMITED, SAVE_FAILED, CONFLICT_LOADED, CONFLICT_FAILED, LOAD_FAILED }

sealed interface FormResult {
    data class Created(val detail: CustomCategoryDetail) : FormResult
    data class Saved(val detail: CustomCategoryDetail) : FormResult
    /** The edited category no longer exists. */
    data object Gone : FormResult
}

data class FormParent(val id: String, val name: String)

/**
 * - [canSubmit]: READY, not submitting, valid input (non-blank name), a parent, edit: changed,
 *   create: under the custom limit as of the form's SELECT (a resend after a lost answer stays allowed).
 * - [submitting]: also true while a version conflict reads the latest detail: inputs and closing are locked.
 * - [usedCount]/[limit]: the custom usage (create); [itemCount]: the edited category's items (rename note).
 */
data class CategoryFormState(
    val mode: FormMode,
    val source: FormSource,
    val phase: FormPhase,
    val parents: List<FormParent>,
    val parentId: String?,
    val parentName: String?,
    val name: String,
    val description: String,
    val examples: List<String>,
    val pendingExample: String,
    val dirty: Boolean,
    val canSubmit: Boolean,
    val submitting: Boolean,
    val fieldErrors: Map<String, FieldError>,
    val message: FormMessage?,
    val usedCount: Int?,
    val limit: Int?,
    val itemCount: Int?,
    val result: FormResult?,
) {
    val limitReached: Boolean get() = mode == FormMode.CREATE && usedCount != null && limit != null && usedCount >= limit

    companion object {
        val Initial = CategoryFormState(
            FormMode.CREATE, FormSource.TAB, FormPhase.LOADING, emptyList(), null, null, "", "", emptyList(), "",
            dirty = false, canSubmit = false, submitting = false, fieldErrors = emptyMap(), message = null,
            usedCount = null, limit = null, itemCount = null, result = null,
        )
    }
}

/**
 * The custom category form (FCategoryAddSheet, FCategoryEditSheet, FProductCategoryCreate).
 *
 * Submitting is one request at a time. A create sends an idempotency key with the payload; the
 * next submit reuses the key when its payload equals the one last sent (a lost answer replays the
 * same category) and takes a new key when the input changed. An edit that hits a version conflict
 * reads the latest detail: if it already holds what this edit asked for, the form ends as saved;
 * otherwise the form is replaced by it ([FormMessage.CONFLICT_LOADED]); if it cannot be read, the
 * draft stays and saving waits ([FormPhase.CONFLICT_UNRESOLVED]).
 */
class CategoryFormPresenter internal constructor(
    private val categories: CategoryRepository,
    private val ids: IdGenerator,
    session: AuthSession,
    dispatcher: CoroutineDispatcher,
) : SessionLane(session, dispatcher) {
    private val mutableState = MutableStateFlow(CategoryFormState.Initial)
    val state: StateFlow<CategoryFormState> = mutableState.asStateFlow()

    // Lane-confined.
    private var editId: String? = null
    private var baseline: CustomCategoryDetail? = null
    private var createParent: String? = null
    private var sentCommand: CategoryCommand? = null
    private var sentKey: String? = null
    private var sentPatchResult: Triple<String, String?, List<String>>? = null
    private var job: Job? = null

    fun startCreate(parentId: String?, source: FormSource, select: CategoryCatalog?) {
        intent {
            reset()
            createParent = parentId
            mutableState.value = CategoryFormState.Initial.copy(mode = FormMode.CREATE, source = source, parentId = parentId)
            if (select != null) applySelect(select) else loadSelect()
        }
    }

    /** [parentName] is the list screen's (CAT-02 has only the parent ID). */
    fun startEdit(customId: String, parentName: String) {
        intent {
            reset()
            editId = customId
            mutableState.value = CategoryFormState.Initial.copy(mode = FormMode.EDIT, parentName = parentName)
            loadDetail(conflict = false)
        }
    }

    fun retryLoad() {
        intent {
            when (mutableState.value.phase) {
                FormPhase.LOAD_FAILED -> if (mutableState.value.mode == FormMode.CREATE) loadSelect() else loadDetail(conflict = false)
                FormPhase.CONFLICT_UNRESOLVED -> loadDetail(conflict = true)
                else -> Unit
            }
        }
    }

    fun setParent(id: String) = edit { s -> if (s.mode != FormMode.CREATE || s.parents.none { it.id == id }) s else s.copy(parentId = id).clear("parentId") }
    fun setName(text: String) = edit { it.copy(name = text).clear("name") }
    fun setDescription(text: String) = edit { it.copy(description = text).clear("description") }
    fun setPendingExample(text: String) = edit { it.copy(pendingExample = text).clear("examples") }

    /** Keyboard done in the example field (D12): blank and already listed examples are ignored, five at most. */
    fun commitExample() = edit { s ->
        val text = s.pendingExample
        when {
            CategoryInputRules.isBlank(text) -> s.copy(pendingExample = "")
            text in s.examples -> s.copy(pendingExample = "")
            s.examples.size >= CategoryInputRules.EXAMPLE_COUNT_LIMIT -> s
            else -> s.copy(examples = s.examples + text, pendingExample = "").clear("examples")
        }
    }

    fun removeExample(index: Int) = edit { s ->
        if (index !in s.examples.indices) s else s.copy(examples = s.examples.filterIndexed { i, _ -> i != index }).clear("examples")
    }

    fun submit() {
        intent {
            val s = mutableState.value
            if (!s.canSubmit) return@intent
            val examples = examplesToSend(s)
            when (s.mode) {
                FormMode.CREATE -> create(s, examples)
                FormMode.EDIT -> update(s, examples)
            }
        }
    }

    /** The platform consumed [CategoryFormState.result] (closed the sheet or moved on). */
    fun consumeResult() { intent { mutableState.value = mutableState.value.copy(result = null) } }

    override fun onSessionChanged(current: SessionSnapshot) {
        reset()
        mutableState.value = CategoryFormState.Initial
    }

    // ---- internals ----

    private fun reset() {
        job?.cancel(); job = null
        editId = null; baseline = null; createParent = null
        sentCommand = null; sentKey = null; sentPatchResult = null
    }

    /** Input changes are ignored while submitting and before an edit form has its baseline. */
    private fun edit(change: (CategoryFormState) -> CategoryFormState) {
        intent {
            val s = mutableState.value
            if (s.submitting || (s.mode == FormMode.EDIT && (s.phase == FormPhase.LOADING || s.phase == FormPhase.LOAD_FAILED))) return@intent
            publish(change(s))
        }
    }

    private fun CategoryFormState.clear(field: String) = if (field in fieldErrors) copy(fieldErrors = fieldErrors - field) else this

    private fun publish(s: CategoryFormState) {
        val dirty = isDirty(s)
        mutableState.value = s.copy(dirty = dirty, canSubmit = canSubmit(s, dirty))
    }

    private fun isDirty(s: CategoryFormState): Boolean = when (s.mode) {
        FormMode.CREATE -> s.name.isNotEmpty() || s.description.isNotEmpty() || s.examples.isNotEmpty() || s.pendingExample.isNotEmpty()
        FormMode.EDIT -> baseline?.let { b ->
            s.name != b.name || s.description != (b.description ?: "") || examplesToSend(s) != b.examples
        } ?: false
    }

    private fun canSubmit(s: CategoryFormState, dirty: Boolean): Boolean {
        if (s.phase != FormPhase.READY || s.submitting || s.parentId == null) return false
        val examples = examplesToSend(s)
        val command = CategoryCommand(s.parentId, s.name, s.description.ifEmpty { null }, examples)
        if (CategoryInputRules.validate(command).isNotEmpty()) return false
        return when (s.mode) {
            FormMode.EDIT -> dirty
            // The usage is the form's SELECT value; it only changes on a definite LIMIT_REACHED, which
            // also drops the kept payload. So a resend after a lost answer is never blocked here.
            FormMode.CREATE -> !s.limitReached
        }
    }

    /** D12: text left in the example field is sent as an example too (blank or duplicate ignored). */
    private fun examplesToSend(s: CategoryFormState): List<String> {
        val pending = s.pendingExample
        return if (CategoryInputRules.isBlank(pending) || pending in s.examples || s.examples.size >= CategoryInputRules.EXAMPLE_COUNT_LIMIT) s.examples
        else s.examples + pending
    }

    private fun loadSelect() {
        mutableState.value = mutableState.value.copy(phase = FormPhase.LOADING, message = null)
        publish(mutableState.value)
        job?.cancel()
        job = request({ categories.catalog(CategoryScope.SELECT) }) { result ->
            job = null
            when (result) {
                is ClientResult.Success -> applySelect(result.value)
                is ClientResult.Failure -> publish(mutableState.value.copy(phase = FormPhase.LOAD_FAILED, message = FormMessage.LOAD_FAILED))
            }
        }
    }

    private fun applySelect(select: CategoryCatalog) {
        val parents = select.groups.map { FormParent(it.id, it.name) }
        val s = mutableState.value
        val parent = (s.parentId ?: createParent)?.takeIf { id -> parents.any { it.id == id } } ?: parents.firstOrNull()?.id
        publish(s.copy(
            phase = FormPhase.READY, parents = parents, parentId = parent, parentName = parents.firstOrNull { it.id == parent }?.name,
            usedCount = select.customUsedCount, limit = select.customLimit,
            message = if (select.customUsedCount >= select.customLimit) FormMessage.LIMIT_REACHED else null,
        ))
    }

    private fun loadDetail(conflict: Boolean) {
        val id = editId ?: return
        val s = mutableState.value
        mutableState.value = if (conflict) s.copy(submitting = true, canSubmit = false) else s.copy(phase = FormPhase.LOADING, message = null, canSubmit = false)
        job?.cancel()
        job = request({ categories.custom(id) }) { result ->
            job = null
            when (result) {
                is ClientResult.Success -> if (conflict) resolveConflict(result.value) else applyDetail(result.value, message = null)
                is ClientResult.Failure -> when {
                    result.error.kind == ErrorKind.NOT_FOUND -> mutableState.value = mutableState.value.copy(submitting = false, result = FormResult.Gone)
                    conflict -> publish(mutableState.value.copy(phase = FormPhase.CONFLICT_UNRESOLVED, submitting = false, message = FormMessage.CONFLICT_FAILED))
                    else -> publish(mutableState.value.copy(phase = FormPhase.LOAD_FAILED, message = FormMessage.LOAD_FAILED))
                }
            }
        }
    }

    private fun applyDetail(detail: CustomCategoryDetail, message: FormMessage?) {
        baseline = detail
        sentPatchResult = null
        publish(mutableState.value.copy(
            phase = FormPhase.READY, submitting = false, parentId = detail.parentId,
            name = detail.name, description = detail.description ?: "", examples = detail.examples, pendingExample = "",
            itemCount = detail.itemCount, fieldErrors = emptyMap(), message = message,
        ))
    }

    private fun resolveConflict(latest: CustomCategoryDetail) {
        val wanted = sentPatchResult
        if (wanted != null && Triple(latest.name, latest.description, latest.examples) == wanted) {
            baseline = latest
            mutableState.value = mutableState.value.copy(phase = FormPhase.READY, submitting = false, canSubmit = false, result = FormResult.Saved(latest))
        } else {
            applyDetail(latest, FormMessage.CONFLICT_LOADED)
        }
    }

    private fun create(s: CategoryFormState, examples: List<String>) {
        val command = CategoryCommand(s.parentId!!, s.name, s.description.ifEmpty { null }, examples)
        val key = if (command == sentCommand) sentKey!! else Uuid.parse(ids.newId()).toString()
        sentCommand = command
        sentKey = key
        mutableState.value = s.copy(submitting = true, canSubmit = false, message = null, fieldErrors = emptyMap())
        job = request({ categories.create(command, key) }) { result ->
            job = null
            when (result) {
                is ClientResult.Success -> {
                    sentCommand = null; sentKey = null
                    mutableState.value = mutableState.value.copy(
                        submitting = false, usedCount = result.value.customUsedCount, limit = result.value.customLimit,
                        result = FormResult.Created(result.value.category),
                    )
                }
                is ClientResult.Failure -> failed(result.error)
            }
        }
    }

    private fun update(s: CategoryFormState, examples: List<String>) {
        val id = editId ?: return
        val b = baseline ?: return
        val description = s.description.ifEmpty { null }
        val patch = CategoryPatch(
            name = s.name.takeIf { it != b.name },
            description = if (description != b.description) DescriptionChange.Set(description) else DescriptionChange.Keep,
            examples = examples.takeIf { it != b.examples },
        )
        if (patch.isEmpty) return
        sentPatchResult = Triple(s.name, description, examples)
        mutableState.value = s.copy(submitting = true, canSubmit = false, message = null, fieldErrors = emptyMap())
        job = request({ categories.update(id, b.version, patch) }) { result ->
            job = null
            when (result) {
                is ClientResult.Success -> {
                    baseline = result.value
                    mutableState.value = mutableState.value.copy(submitting = false, result = FormResult.Saved(result.value))
                }
                is ClientResult.Failure -> when {
                    result.error.code == "CATEGORY_VERSION_CONFLICT" -> loadDetail(conflict = true)
                    result.error.kind == ErrorKind.NOT_FOUND -> mutableState.value = mutableState.value.copy(submitting = false, result = FormResult.Gone)
                    else -> failed(result.error)
                }
            }
        }
    }

    private fun failed(error: ClientError) {
        // A definite refusal leaves no receipt on the server: the next submit needs no replay key.
        // Only an answer of unknown outcome (connection, timeout, server error) keeps the key and payload.
        if (error.kind !in UNKNOWN_OUTCOME) { sentCommand = null; sentKey = null }
        val s = mutableState.value.copy(submitting = false)
        publish(when {
            error.code == "CATEGORY_NAME_DUPLICATE" -> s.copy(fieldErrors = mapOf("name" to FieldError.DUPLICATE))
            error.code == "CATEGORY_LIMIT_REACHED" -> s.copy(message = FormMessage.LIMIT_REACHED, usedCount = s.limit ?: s.usedCount)
            error.kind == ErrorKind.RATE_LIMITED -> s.copy(message = FormMessage.RATE_LIMITED)
            error.kind == ErrorKind.VALIDATION -> {
                val fields = buildMap {
                    error.fields.forEach { f ->
                        when (f) {
                            "name", "description", "examples" -> put(f, FieldError.INVALID_CHARS)
                            "parentId" -> put(f, FieldError.PARENT)
                        }
                    }
                }
                if (fields.isEmpty()) s.copy(message = FormMessage.SAVE_FAILED) else s.copy(fieldErrors = fields)
            }
            else -> s.copy(message = FormMessage.SAVE_FAILED)
        })
    }

    private companion object {
        val UNKNOWN_OUTCOME = setOf(ErrorKind.NETWORK, ErrorKind.TIMEOUT, ErrorKind.SERVER, ErrorKind.INVALID_RESPONSE, ErrorKind.UNAVAILABLE)
    }
}
