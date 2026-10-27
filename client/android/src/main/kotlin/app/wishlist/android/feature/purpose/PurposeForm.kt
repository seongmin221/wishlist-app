package app.wishlist.android.feature.purpose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import app.wishlist.android.R
import app.wishlist.android.designsystem.*
import app.wishlist.android.designsystem.overlay.*
import app.wishlist.android.feature.category.CategoryText
import app.wishlist.shared.model.PurposeDetail
import app.wishlist.shared.model.PurposeStyleKeys
import app.wishlist.shared.presentation.*
import kotlinx.coroutines.flow.drop

internal fun purposeFormDismiss(state: PurposeFormState): SheetDismiss = when {
    state.submitting -> SheetDismiss.Block
    state.dirty -> SheetDismiss.Confirm
    else -> SheetDismiss.Allow
}

internal class PurposeFieldSync {
    private var baselineApplied = false
    private var conflictShown = false
    fun shouldReplace(state: PurposeFormState): Boolean {
        val baseline = state.mode == FormMode.EDIT && state.phase == FormPhase.READY && !baselineApplied
        if (baseline) baselineApplied = true
        val conflict = state.message == FormMessage.CONFLICT_LOADED && !conflictShown
        conflictShown = state.message == FormMessage.CONFLICT_LOADED
        return baseline || conflict
    }
}

/** Local IME state leads while typing; a conflict reload is the only subsequent replacement. */
@Composable
internal fun rememberPurposeFields(form: PurposeFormPresenter): Pair<TextFieldState, TextFieldState> {
    val name = remember(form) { TextFieldState(form.state.value.name) }
    val description = remember(form) { TextFieldState(form.state.value.description) }
    LaunchedEffect(form) {
        val sync = PurposeFieldSync()
        form.state.collect { s ->
            if (sync.shouldReplace(s)) {
                if (name.text.toString() != s.name) name.setTextAndPlaceCursorAtEnd(s.name)
                if (description.text.toString() != s.description) description.setTextAndPlaceCursorAtEnd(s.description)
            }
        }
    }
    LaunchedEffect(form) { snapshotFlow { name.text.toString() }.drop(1).collect(form::changeName) }
    LaunchedEffect(form) { snapshotFlow { description.text.toString() }.drop(1).collect(form::changeDescription) }
    return name to description
}

internal fun OverlayHostState.showPurposeCreate(form: PurposeFormPresenter, discard: WLDialogSpec, onCreated: (PurposeDetail) -> Unit): Boolean {
    form.startCreate()
    return showSheet(draggable = true, dismissGuard = { purposeFormDismiss(form.state.value) },
        onConfirmDismiss = { showDialog(discard) }, selfLaidOut = true, accountScoped = true) {
        PurposeCreateForm(form, discard, onCreated)
    }
}

@Composable
private fun PurposeCreateForm(form: PurposeFormPresenter, discard: WLDialogSpec, onCreated: (PurposeDetail) -> Unit) {
    val overlay = LocalOverlayHostState.current
    val state by form.state.collectAsState()
    val fields = rememberPurposeFields(form)
    var handled by remember(form) { mutableStateOf(false) }
    LaunchedEffect(state.result) {
        state.result?.let { if (!handled) { handled = true; overlay.dismiss(); onCreated(it) } }
    }
    val close = {
        when (purposeFormDismiss(form.state.value)) {
            SheetDismiss.Allow -> overlay.dismiss()
            SheetDismiss.Confirm -> overlay.showDialog(discard)
            SheetDismiss.Block -> false
        }
        Unit
    }
    WLSheetBody(
        header = { WLSheetHeader(stringResource(R.string.purpose_create), onClose = close) },
        footer = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                WLButton(stringResource(R.string.dialog_cancel), WLButtonKind.Secondary, close, Modifier.weight(1f), enabled = !state.submitting)
                WLButton(stringResource(R.string.purpose_create_action), WLButtonKind.Primary, form::submit, Modifier.weight(2f), enabled = state.canSubmit, loading = state.submitting)
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            WLInput(fields.first, label = stringResource(R.string.purpose_name_label), hint = stringResource(R.string.purpose_name_hint),
                placeholder = stringResource(R.string.purpose_name_placeholder), maxLength = 40, limitUnit = InputLimitUnit.CodePoint, enabled = !state.submitting)
            PurposeFieldError(state.fieldErrors["name"])
            WLInput(fields.second, label = stringResource(R.string.purpose_description_label), hint = stringResource(R.string.purpose_description_hint),
                placeholder = stringResource(R.string.purpose_description_placeholder), maxLength = 200, limitUnit = InputLimitUnit.CodePoint,
                singleLine = false, enabled = !state.submitting)
            PurposeFieldError(state.fieldErrors["description"])
            PurposeStylePicker(state, form)
            WLText(stringResource(R.string.purpose_create_note), WLType.label, color = LocalWLColors.current.textSecondary)
            PurposeFormMessage(state, form)
        }
    }
}

@Composable
internal fun PurposeFieldError(error: FieldError?) {
    if (error != null) WLText(stringResource(CategoryText.fieldError(error)), WLType.label, color = WishlistTokens.Danger.surface)
}

@Composable
internal fun PurposeFormMessage(state: PurposeFormState, form: PurposeFormPresenter) {
    state.message?.let { message ->
        val text = when (message) {
            FormMessage.LIMIT_REACHED -> R.string.purpose_limit
            FormMessage.RATE_LIMITED -> R.string.purpose_rate_limit
            FormMessage.SAVE_FAILED -> R.string.purpose_save_failed
            else -> CategoryText.message(message)
        }
        WLText(stringResource(text), WLType.body)
        if (state.phase == FormPhase.CONFLICT_UNRESOLVED || state.phase == FormPhase.LOAD_FAILED) {
            WLButton(stringResource(R.string.detail_retry), WLButtonKind.Secondary, form::retryLoad)
        }
    }
}

@Composable
internal fun PurposeStylePicker(state: PurposeFormState, form: PurposeFormPresenter) {
    val c = LocalWLColors.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WLText(stringResource(R.string.purpose_color), WLType.label, color = c.textSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PurposeStyleKeys.colorKeys.forEach { key ->
                val selected = state.colorKey == key
                val label = stringResource(colorLabel(key))
                Box(Modifier.size(44.dp).drawBehind {
                    if (selected) drawCircle(c.text, radius = 24.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                }
                    .clickable(enabled = !state.submitting, role = Role.RadioButton) { form.chooseColor(key) }
                    .semantics { contentDescription = label; this.selected = selected }, contentAlignment = Alignment.Center) {
                    purposePaletteColor(key)?.let { PurposeDot(it, size = 40.dp) }
                }
            }
        }
        WLText(stringResource(R.string.purpose_icon), WLType.label, color = c.textSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PurposeStyleKeys.iconKeys.forEach { key ->
                val selected = state.iconKey == key
                val label = stringResource(iconLabel(key))
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(if (selected) c.text else c.sheetField)
                    .clickable(enabled = !state.submitting, role = Role.RadioButton) { form.chooseIcon(key) }
                    .semantics { contentDescription = label; this.selected = selected }, contentAlignment = Alignment.Center) {
                    WLIcon(purposeIcon(key), color = if (selected) c.onInverse else c.text)
                }
            }
        }
    }
}

private fun colorLabel(key: String) = when (key) {
    "coral" -> R.string.purpose_color_coral
    "mustard" -> R.string.purpose_color_mustard
    "periwinkle" -> R.string.purpose_color_periwinkle
    "cyan" -> R.string.purpose_color_cyan
    "mint" -> R.string.purpose_color_mint
    else -> R.string.purpose_color_pink
}
private fun iconLabel(key: String) = when (key) {
    "heart" -> R.string.purpose_icon_heart
    "home" -> R.string.purpose_icon_home
    "plane" -> R.string.purpose_icon_plane
    "gift" -> R.string.purpose_icon_gift
    "tent" -> R.string.purpose_icon_tent
    "music" -> R.string.purpose_icon_music
    "star" -> R.string.purpose_icon_star
    else -> R.string.purpose_icon_book
}
