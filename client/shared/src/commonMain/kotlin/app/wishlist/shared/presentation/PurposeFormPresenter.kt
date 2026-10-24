@file:OptIn(kotlin.experimental.ExperimentalObjCName::class)
package app.wishlist.shared.presentation

import app.wishlist.shared.core.*
import app.wishlist.shared.model.*
import app.wishlist.shared.repository.PurposeRepository
import app.wishlist.shared.domain.PurposeInputRules
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.native.ObjCName

data class PurposeFormState(
    val mode: FormMode = FormMode.CREATE, val phase: FormPhase = FormPhase.READY,
    val name: String = "",
    @property:ObjCName(name = "purposeDescription", swiftName = "purposeDescription") val description: String = "",
    val colorKey: String = "coral", val iconKey: String = "heart", val dirty: Boolean = false,
    val canSubmit: Boolean = false, val submitting: Boolean = false, val message: FormMessage? = null,
    val fieldErrors: Map<String, FieldError> = emptyMap(), val result: PurposeDetail? = null, val gone: Boolean = false,
)
class PurposeFormPresenter internal constructor(
    private val purposes: PurposeRepository, private val ids: IdGenerator,
    session: AuthSession, dispatcher: CoroutineDispatcher,
) : SessionLane(session, dispatcher) {
    private val mutable = MutableStateFlow(PurposeFormState())
    val state: StateFlow<PurposeFormState> = mutable.asStateFlow()
    private var baseline: PurposeDetail? = null
    private var sentCommand: PurposeCommand? = null
    private var sentKey: String? = null
    private var attemptedEdit: PurposeCommand? = null
    private var job: Job? = null
    private var limitReached = false

    fun startCreate() { intent {
        reset()
        mutable.value = PurposeFormState(phase = FormPhase.LOADING)
        loadUsage()
    } }
    fun startEdit(detail: PurposeDetail) { intent {
        reset()
        applyDetail(detail)
    } }
    fun changeName(value: String) = edit("name") { it.copy(name = value) }
    fun changeDescription(value: String) = edit("description") { it.copy(description = value) }
    fun chooseColor(key: String) = edit("colorKey") { if (key in PurposeStyleKeys.colorKeys) it.copy(colorKey = key) else it }
    fun chooseIcon(key: String) = edit("iconKey") { if (key in PurposeStyleKeys.iconKeys) it.copy(iconKey = key) else it }

    private fun edit(field: String, change: (PurposeFormState) -> PurposeFormState) { intent {
        val s = mutable.value
        if (s.submitting || s.result != null || s.gone || observed.accountId == null) return@intent
        publish(change(s).copy(fieldErrors = s.fieldErrors - field))
    } }
    fun submit() { intent {
        val s = mutable.value
        if (!s.canSubmit || s.submitting) return@intent
        val command = command(s)
        mutable.value = s.copy(submitting = true, canSubmit = false, message = null, fieldErrors = emptyMap())
        if (s.mode == FormMode.CREATE) {
            val key = if (sentCommand == command) sentKey!! else ids.newId()
            sentCommand = command; sentKey = key
            job = request({ purposes.create(command, key) }) { result ->
                job = null
                when (result) {
                    is ClientResult.Success -> {
                        sentCommand = null; sentKey = null
                        mutable.value = mutable.value.copy(submitting = false, result = result.value.purpose)
                    }
                    is ClientResult.Failure -> failed(result.error)
                }
            }
        } else {
            val b = baseline ?: return@intent
            val patch = PurposePatch(
                name = command.name.takeIf { it != b.name },
                description = if (command.description != b.description) DescriptionChange.Set(command.description) else DescriptionChange.Keep,
                colorKey = command.colorKey.takeIf { it != b.colorKey },
                iconKey = command.iconKey.takeIf { it != b.iconKey },
            )
            attemptedEdit = command
            job = request({ purposes.update(b.id, b.version, patch) }) { result ->
                job = null
                when (result) {
                    is ClientResult.Success -> mutable.value = mutable.value.copy(submitting = false, result = result.value)
                    is ClientResult.Failure -> when (result.error.code) {
                        "PURPOSE_VERSION_CONFLICT" -> loadConflict()
                        "PURPOSE_NOT_FOUND" -> markGone()
                        else -> failed(result.error)
                    }
                }
            }
        }
    } }
    fun retryLoad() { intent {
        if (mutable.value.submitting || mutable.value.gone || observed.accountId == null) return@intent
        when (mutable.value.phase) {
            FormPhase.LOAD_FAILED -> loadUsage()
            FormPhase.CONFLICT_UNRESOLVED -> loadConflict()
            else -> Unit
        }
    } }
    override fun onSessionChanged(current: SessionSnapshot) {
        reset()
        mutable.value = PurposeFormState()
    }
    private fun reset() {
        job?.cancel(); job = null
        baseline = null; sentCommand = null; sentKey = null; attemptedEdit = null; limitReached = false
    }
    private fun loadUsage() {
        if (observed.accountId == null) return
        mutable.value = mutable.value.copy(phase = FormPhase.LOADING, message = null)
        job = request({ purposes.summary() }) { result ->
            job = null
            when (result) {
                is ClientResult.Success -> {
                    limitReached = result.value.activeCount >= result.value.purposeLimit
                    publish(mutable.value.copy(phase = FormPhase.READY, message = if (limitReached) FormMessage.LIMIT_REACHED else null))
                }
                is ClientResult.Failure -> publish(mutable.value.copy(phase = FormPhase.LOAD_FAILED, message = FormMessage.LOAD_FAILED))
            }
        }
    }
    private fun loadConflict() {
        val b = baseline ?: return
        mutable.value = mutable.value.copy(submitting = true, canSubmit = false, message = null)
        job = request({ purposes.detail(b.id) }) { result ->
            job = null
            when (result) {
                is ClientResult.Success -> {
                    val latest = result.value
                    if (attemptedEdit == PurposeCommand(latest.name, latest.description, latest.colorKey, latest.iconKey)) {
                        mutable.value = mutable.value.copy(submitting = false, phase = FormPhase.READY, result = latest)
                    } else applyDetail(latest, FormMessage.CONFLICT_LOADED)
                }
                is ClientResult.Failure -> if (result.error.code == "PURPOSE_NOT_FOUND") markGone()
                else publish(mutable.value.copy(submitting = false, phase = FormPhase.CONFLICT_UNRESOLVED, message = FormMessage.CONFLICT_FAILED))
            }
        }
    }
    private fun applyDetail(detail: PurposeDetail, message: FormMessage? = null) {
        baseline = detail
        publish(PurposeFormState(mode = FormMode.EDIT, name = detail.name, description = detail.description.orEmpty(),
            colorKey = detail.colorKey, iconKey = detail.iconKey, message = message))
    }
    private fun command(s: PurposeFormState) = PurposeCommand(s.name, s.description.ifEmpty { null }, s.colorKey, s.iconKey)
    private fun publish(s: PurposeFormState) {
        val dirty = if (s.mode == FormMode.CREATE) s.name.isNotEmpty() || s.description.isNotEmpty() || s.colorKey != "coral" || s.iconKey != "heart"
        else baseline?.let { command(s) != PurposeCommand(it.name, it.description, it.colorKey, it.iconKey) } ?: false
        val permitted = s.mode == FormMode.CREATE || baseline?.allowedActions?.contains("EDIT") == true
        mutable.value = s.copy(dirty = dirty, canSubmit = observed.accountId != null && permitted && s.phase == FormPhase.READY &&
            !s.submitting && !s.gone && s.result == null && !limitReached && PurposeInputRules.validate(command(s)).isEmpty() &&
            s.fieldErrors.isEmpty() && (s.mode == FormMode.CREATE || dirty))
    }
    private fun markGone() {
        mutable.value = mutable.value.copy(submitting = false, canSubmit = false, gone = true)
    }
    private fun failed(error: ClientError) {
        if (error.kind !in setOf(ErrorKind.NETWORK, ErrorKind.TIMEOUT, ErrorKind.SERVER, ErrorKind.INVALID_RESPONSE, ErrorKind.UNAVAILABLE)) {
            sentCommand = null; sentKey = null
        }
        if (error.code == "PURPOSE_LIMIT_REACHED") limitReached = true
        val message = when {
            limitReached -> FormMessage.LIMIT_REACHED
            error.kind == ErrorKind.RATE_LIMITED -> FormMessage.RATE_LIMITED
            else -> FormMessage.SAVE_FAILED
        }
        publish(mutable.value.copy(submitting = false, message = message,
            fieldErrors = error.fields.filter { it in setOf("name", "description", "colorKey", "iconKey") }.associateWith { FieldError.INVALID_CHARS }))
    }
}
