package app.wishlist.android.feature.category

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wishlist.android.R
import app.wishlist.android.designsystem.InputLimitUnit
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.WLButton
import app.wishlist.android.designsystem.WLButtonKind
import app.wishlist.android.designsystem.WLChip
import app.wishlist.android.designsystem.WLCircleButton
import app.wishlist.android.designsystem.WLIcon
import app.wishlist.android.designsystem.WLInput
import app.wishlist.android.designsystem.WLLineIcon
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.WLType
import app.wishlist.android.designsystem.WishlistTokens
import app.wishlist.android.designsystem.overlay.LocalOverlayHostState
import app.wishlist.android.designsystem.overlay.OverlayHostState
import app.wishlist.android.designsystem.overlay.SheetDismiss
import app.wishlist.android.designsystem.overlay.WLDialogSpec
import app.wishlist.android.designsystem.overlay.WLSheetBody
import app.wishlist.android.designsystem.overlay.WLSheetHeader
import app.wishlist.android.designsystem.overlay.WLSheetTitleStyle
import app.wishlist.shared.domain.CategoryInputRules
import app.wishlist.shared.presentation.CategoryFormPresenter
import app.wishlist.shared.presentation.CategoryFormState
import app.wishlist.shared.presentation.FormMode
import app.wishlist.shared.presentation.FormPhase
import app.wishlist.shared.presentation.FormResult
import app.wishlist.shared.presentation.FormSource
import kotlinx.coroutines.flow.drop

/** What a user dismiss of the form sheet does: a save in flight blocks it, unsaved input asks first. */
internal fun formDismiss(state: CategoryFormState): SheetDismiss = when {
    state.submitting -> SheetDismiss.Block
    state.dirty -> SheetDismiss.Confirm
    else -> SheetDismiss.Allow
}

/** "변경 사항을 버릴까요?" (계속 편집 / 버리기): 버리면 [onDiscard]. */
@Composable
internal fun rememberDiscardSpec(onDiscard: () -> Unit): WLDialogSpec {
    val title = stringResource(R.string.category_form_discard_title)
    val body = stringResource(R.string.category_form_discard_body)
    val keep = stringResource(R.string.category_form_discard_keep)
    val discard = stringResource(R.string.category_form_discard_confirm)
    val latest by rememberUpdatedState(onDiscard)
    return remember(title, body, keep, discard) {
        WLDialogSpec(title, listOf(body), keep, discard, WLButtonKind.Primary) { latest() }
    }
}

/**
 * Opens the create (FCategoryAddSheet) or edit (FCategoryEditSheet) sheet over [form]. [start] picks the
 * mode (`startCreate`/`startEdit`); [onResult] gets Created/Saved/Gone after the sheet began closing.
 */
internal fun OverlayHostState.showCategoryForm(
    form: CategoryFormPresenter,
    discard: WLDialogSpec,
    start: (CategoryFormPresenter) -> Unit,
    onResult: (FormResult) -> Unit,
): Boolean {
    start(form)
    return showSheet(
        draggable = true,
        dismissGuard = { formDismiss(form.state.value) },
        onConfirmDismiss = { showDialog(discard) },
        selfLaidOut = true,
        accountScoped = true,
    ) { CategoryFormContent(form, discard, onResult, header = null) }
}

/**
 * The form body shared by the tab sheets and the picker's in-sheet page. [header] replaces the sheet
 * title row (the picker draws back + close); null draws "신규 카테고리"/"세부 카테고리 편집" + close.
 */
@Composable
internal fun CategoryFormContent(
    form: CategoryFormPresenter,
    discard: WLDialogSpec,
    onResult: (FormResult) -> Unit,
    header: (@Composable (requestBack: () -> Unit, requestCloseSheet: () -> Unit) -> Unit)?,
    onCancel: (() -> Unit)? = null,
    /** Asked before closing the whole sheet with unsaved input (the picker's X); null = [discard]. */
    closeDiscard: WLDialogSpec? = null,
) {
    val overlay = LocalOverlayHostState.current
    val c = LocalWLColors.current
    val state by form.state.collectAsState()
    // The opening's Presenter is fresh (CategoryFormSlot): the fields start empty like it.
    val name = remember(form) { TextFieldState() }
    val description = remember(form) { TextFieldState() }
    val example = remember(form) { TextFieldState() }

    // Presenter → fields only for the edit baseline and a 409 reload ([FormFieldSync]); never while the
    // user types (a create's SELECT LOADING → READY must not drop input the Presenter has not seen yet).
    LaunchedEffect(form) {
        val sync = FormFieldSync()
        form.state.collect { s ->
            if (sync.shouldReplace(s)) {
                if (name.text.toString() != s.name) name.setTextAndPlaceCursorAtEnd(s.name)
                if (description.text.toString() != s.description) description.setTextAndPlaceCursorAtEnd(s.description)
                if (example.text.toString() != s.pendingExample) example.setTextAndPlaceCursorAtEnd(s.pendingExample)
            }
        }
    }
    // Fields → Presenter (the first value is the pushed one, skipped).
    LaunchedEffect(form) { snapshotFlow { name.text.toString() }.drop(1).collect { form.setName(it) } }
    LaunchedEffect(form) { snapshotFlow { description.text.toString() }.drop(1).collect { form.setDescription(it) } }
    LaunchedEffect(form) { snapshotFlow { example.text.toString() }.drop(1).collect { form.setPendingExample(it) } }

    val latestResult by rememberUpdatedState(onResult)
    LaunchedEffect(state.result) {
        val result = state.result ?: return@LaunchedEffect
        overlay.dismissAll()
        form.consumeResult()
        latestResult(result)
    }

    val requestClose = {
        when (formDismiss(form.state.value)) {
            SheetDismiss.Allow -> { if (onCancel != null) onCancel() else overlay.dismiss() }
            SheetDismiss.Confirm -> overlay.showDialog(discard)
            SheetDismiss.Block -> Unit
        }
        Unit
    }
    // The whole sheet (the picker page's X): unsaved input asks first, a save in flight blocks it.
    val requestCloseSheet = {
        when (formDismiss(form.state.value)) {
            SheetDismiss.Allow -> overlay.dismiss()
            SheetDismiss.Confirm -> overlay.showDialog(closeDiscard ?: discard)
            SheetDismiss.Block -> Unit
        }
        Unit
    }
    val editable = !state.submitting && !(state.mode == FormMode.EDIT && state.phase != FormPhase.READY && state.phase != FormPhase.CONFLICT_UNRESOLVED)

    WLSheetBody(
        header = {
            if (header != null) header(requestClose, requestCloseSheet) else {
                val title = stringResource(if (state.mode == FormMode.EDIT) R.string.category_form_edit_title else R.string.category_form_create_title)
                WLSheetHeader(title, onClose = requestClose)
            }
        },
        footer = { FormButtons(state, form, example, requestClose) },
        headerDivider = header != null, // the picker's create page (board line under its header)
    ) { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s20)) {
        when (state.mode) {
            FormMode.CREATE -> if (state.source == FormSource.TAB) ParentChips(state, form, editable)
                else state.parentName?.let { WLText(keepAllBreaks(stringResource(R.string.category_form_picker_note, it)), CategoryCaption.copy(fontSize = 14.sp), color = c.textSecondary) }
            FormMode.EDIT -> state.parentName?.let { parent ->
                WLText(stringResource(R.string.category_form_edit_scope, parent, state.itemCount ?: 0), CategoryCaption, color = c.textSecondary)
            }
        }
        if (state.phase == FormPhase.LOAD_FAILED) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s12)) {
                WLText(stringResource(R.string.detail_error_network), CategoryCaption, Modifier.weight(1f), color = c.textSecondary)
                WLButton(stringResource(R.string.detail_retry), WLButtonKind.Secondary, onClick = form::retryLoad)
            }
        }
        FieldBlock(state.fieldErrors["name"]) {
            WLInput(
                name, label = stringResource(R.string.category_form_name_label), hint = stringResource(R.string.category_form_name_hint),
                placeholder = stringResource(R.string.category_form_name_placeholder),
                maxLength = CategoryInputRules.NAME_LIMIT, limitUnit = InputLimitUnit.CodePoint, enabled = editable,
            )
        }
        FieldBlock(state.fieldErrors["description"]) {
            WLInput(
                description, label = stringResource(R.string.category_form_desc_label), hint = stringResource(R.string.category_form_desc_hint),
                placeholder = stringResource(R.string.category_form_desc_placeholder), singleLine = false, minLines = 2,
                maxLength = CategoryInputRules.DESCRIPTION_LIMIT, limitUnit = InputLimitUnit.CodePoint, enabled = editable,
            )
        }
        FieldBlock(state.fieldErrors["examples"]) {
            Examples(state, form, example, editable)
        }
        val note = when {
            state.mode == FormMode.CREATE && state.source == FormSource.TAB -> stringResource(R.string.category_form_create_note)
            state.mode == FormMode.CREATE && state.usedCount != null && state.limit != null ->
                stringResource(R.string.category_form_usage, state.usedCount!!, state.limit!!)
            state.mode == FormMode.EDIT && (state.itemCount ?: 0) > 0 -> stringResource(R.string.category_form_rename_note, state.itemCount!!)
            else -> null
        }
        note?.let { WLText(keepAllBreaks(it), CategoryCaption, color = c.textSecondary) }
        state.message?.let { message ->
            if (message != app.wishlist.shared.presentation.FormMessage.LOAD_FAILED) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s12)) {
                    WLText(keepAllBreaks(stringResource(CategoryText.message(message))), WLType.body, Modifier.weight(1f), color = c.text)
                    if (state.phase == FormPhase.CONFLICT_UNRESOLVED) WLButton(stringResource(R.string.detail_retry), WLButtonKind.Secondary, onClick = form::retryLoad)
                }
            }
        }
    } }
}

/** 취소 · 만들기/저장(보드: 시트 하단에 고정, 1 : 2). */
@Composable
private fun FormButtons(state: CategoryFormState, form: CategoryFormPresenter, example: TextFieldState, requestClose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s12)) {
        WLButton(stringResource(R.string.dialog_cancel), WLButtonKind.Secondary, onClick = requestClose, modifier = Modifier.weight(1f), enabled = !state.submitting)
        WLButton(
            stringResource(if (state.mode == FormMode.EDIT) R.string.category_form_save else R.string.category_form_create),
            WLButtonKind.Primary,
            onClick = {
                form.setPendingExample(example.text.toString())
                form.submit()
            },
            modifier = Modifier.weight(2f),
            enabled = state.canSubmit,
            // While a save (or the 409 re-read) waits: a small indicator instead of the text, not pressable.
            loading = state.submitting,
        )
    }
}

/** A field and its error line under it (13, danger red #C62828: no board shows a field error; conservative choice). */
@Composable
private fun FieldBlock(error: app.wishlist.shared.presentation.FieldError?, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        content()
        if (error != null) WLText(stringResource(CategoryText.fieldError(error)), CategoryCaption, color = WishlistTokens.Danger.surface)
    }
}

/** 상위 카테고리 · 필수: SELECT의 11개 칩(지금 고른 것은 먹색). 받는 동안은 skeleton 칩. */
@Composable
private fun ParentChips(state: CategoryFormState, form: CategoryFormPresenter, editable: Boolean) {
    val c = LocalWLColors.current
    Column(verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8)) {
        WLText(stringResource(R.string.category_form_parent_label), CategoryCaption, color = c.textSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8), verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8)) {
            if (state.parents.isEmpty()) {
                listOf(80, 110, 70, 96, 120, 64).forEach { SkeletonBar(it.dp, height = 40.dp) }
            } else {
                state.parents.forEach { parent ->
                    WLChip(parent.name, onClick = { if (editable) form.setParent(parent.id) }, selected = parent.id == state.parentId)
                }
            }
        }
        state.fieldErrors["parentId"]?.let { WLText(stringResource(CategoryText.fieldError(it)), CategoryCaption, color = WishlistTokens.Danger.surface) }
    }
}

/** 포함 예시 · 선택: 칩(× 빼기) + 입력칸(완료 키로 칩, 5개면 숨김, D12). */
@Composable
private fun Examples(state: CategoryFormState, form: CategoryFormPresenter, field: TextFieldState, editable: Boolean) {
    val c = LocalWLColors.current
    val removeLabel = stringResource(R.string.category_form_example_remove)
    Column(verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            WLText(stringResource(R.string.category_form_examples_label), WLType.body.copy(fontSize = 13.sp), color = c.textSecondary)
            WLText(
                if (state.mode == FormMode.EDIT || state.examples.isNotEmpty()) stringResource(R.string.category_form_examples_count, state.examples.size)
                else stringResource(R.string.category_form_examples_hint),
                WLType.label, color = c.textSecondary,
            )
        }
        if (state.examples.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8), verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8)) {
                state.examples.forEachIndexed { i, text ->
                    Row(
                        Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(WishlistTokens.Radius.pill)).background(c.sheetField)
                            .padding(start = 14.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s4),
                    ) {
                        WLText(text, WLType.body, maxLines = 1)
                        Box(
                            Modifier.size(28.dp).clip(RoundedCornerShape(14.dp))
                                .semantics { contentDescription = removeLabel }
                                .clickable(enabled = editable, role = Role.Button) { form.removeExample(i) },
                            contentAlignment = Alignment.Center,
                        ) { WLIcon(WLLineIcon.Close, size = 14.dp) }
                    }
                }
            }
        }
        if (state.examples.size < CategoryInputRules.EXAMPLE_COUNT_LIMIT) {
            WLInput(
                field, placeholder = stringResource(R.string.category_form_example_placeholder),
                maxLength = CategoryInputRules.EXAMPLE_LIMIT, limitUnit = InputLimitUnit.CodePoint, enabled = editable,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                onKeyboardAction = {
                    form.setPendingExample(field.text.toString())
                    form.commitExample()
                    field.setTextAndPlaceCursorAtEnd("")
                },
            )
        }
    }
}

/**
 * The picker's create page header (FProductCategoryCreate): back (to the chips) + "신규 카테고리" + close,
 * both 44dp tile circles like the sheet header's close (sheet field color, not the card color).
 */
@Composable
internal fun PickerCreateHeader(onBack: () -> Unit, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8)) {
        SheetTileButton(WLLineIcon.Back, stringResource(R.string.category_back), onBack)
        WLText(stringResource(R.string.category_form_create_title), WLSheetTitleStyle, Modifier.weight(1f))
        SheetTileButton(WLLineIcon.Close, stringResource(R.string.category_close), onClose)
    }
}

/** 44dp circle on the sheet's field color (보드 시트 머리 버튼). */
@Composable
internal fun SheetTileButton(icon: WLLineIcon, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(WishlistTokens.Space.minTouch)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(LocalWLColors.current.sheetField)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { WLIcon(icon) }
}
