package app.wishlist.android.feature.category

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.wishlist.android.R
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.WLAddChip
import app.wishlist.android.designsystem.WLButton
import app.wishlist.android.designsystem.WLButtonKind
import app.wishlist.android.designsystem.WLChip
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.WLType
import app.wishlist.android.designsystem.WishlistTokens
import app.wishlist.android.designsystem.overlay.LocalOverlayHostState
import app.wishlist.android.designsystem.overlay.OverlayHostState
import app.wishlist.android.designsystem.overlay.SheetDismiss
import app.wishlist.android.designsystem.overlay.WLDialogSpec
import app.wishlist.android.designsystem.overlay.WLSheetHeader
import app.wishlist.android.feature.detail.DetailText
import app.wishlist.shared.presentation.CategoryFormPresenter
import app.wishlist.shared.presentation.CategoryPickerPresenter
import app.wishlist.shared.presentation.FormResult
import app.wishlist.shared.presentation.FormSource

/** Picker page: the chips, or the in-sheet create form (`+ 카테고리`). */
internal class PickerPage {
    var creating by mutableStateOf(false)
}

/**
 * FProductCategoryPicker / Create (D1, D14): a rail + chips sheet over CAT-01 SELECT. A chip picks and
 * closes; `+ 카테고리` turns the same sheet into the create form (back returns to the chips, discarding
 * after a check if edited); a create picks the new category. The pick arrives in [picker]'s `result`.
 * C5 opens it from the DEBUG demo only; C8 opens it from the product edit draft.
 */
internal fun OverlayHostState.showCategoryPicker(
    picker: CategoryPickerPresenter,
    form: CategoryFormPresenter,
    selectedId: String?,
    page: PickerPage,
    discardToChips: WLDialogSpec,
): Boolean {
    picker.open(selectedId)
    page.creating = false
    return showSheet(
        draggable = true,
        dismissGuard = { if (page.creating) formDismiss(form.state.value) else SheetDismiss.Allow },
        onConfirmDismiss = { showDialog(discardToChips) },
    ) { CategoryPickerContent(picker, form, page, discardToChips) }
}

@Composable
private fun CategoryPickerContent(picker: CategoryPickerPresenter, form: CategoryFormPresenter, page: PickerPage, discard: WLDialogSpec) {
    val overlay = LocalOverlayHostState.current
    val c = LocalWLColors.current
    val state by picker.state.collectAsState()
    // Height changes between the two pages: 300 `emphasized` (motion.md, same-sheet step).
    Column(Modifier.fillMaxWidth().animateContentSize(tween(WishlistTokens.Motion.headerCollapseScroll, easing = WishlistTokens.Curve.emphasized))) {
        if (page.creating) {
            CategoryFormContent(
                form, discard,
                onResult = { if (it is FormResult.Created) picker.onCreated(it.detail) },
                header = { requestClose -> PickerCreateHeader(onBack = requestClose, onClose = requestClose) },
                onCancel = { page.creating = false },
            )
            return@Column
        }
        WLSheetHeader(stringResource(R.string.category_picker_title), onClose = { overlay.dismiss() })
        Box(Modifier.padding(top = WishlistTokens.Space.s16)) { CategoryDivider() }
        val catalog = state.catalog
        when {
            catalog == null && state.error != null -> Row(
                Modifier.padding(vertical = WishlistTokens.Space.s24),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s12),
            ) {
                WLText(stringResource(DetailText.errorText(state.error!!)), WLType.body, Modifier.weight(1f), color = c.textSecondary)
                WLButton(stringResource(R.string.detail_retry), WLButtonKind.Secondary, onClick = picker::retry)
            }
            catalog == null -> Column(Modifier.padding(vertical = WishlistTokens.Space.s16), verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8)) {
                listOf(160, 120, 140).forEach { SkeletonBar(it.dp, height = 40.dp) }
            }
            else -> Row(Modifier.fillMaxWidth()) {
                // 레일 120(보드 FProductCategoryPicker): 시트 좌우 여백 안쪽이라 글자 시작은 0.
                Column(Modifier.width(120.dp).padding(top = 6.dp, bottom = WishlistTokens.Space.s16)) {
                    catalog.groups.forEach { group ->
                        val on = group.id == state.currentGroupId
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .semantics { selected = on }
                                .clickable(role = Role.Tab) { picker.showGroup(group.id) }
                                .drawBehind { if (on) drawRect(c.text, size = Size(3.dp.toPx(), size.height)) }
                                .padding(start = 17.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            WLText(
                                keepAllBreaks(group.name),
                                WLType.body.copy(fontSize = 15.sp, lineHeight = 1.3.em, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal),
                                color = if (on) c.text else c.textSecondary,
                            )
                        }
                    }
                }
                val group = catalog.groups.firstOrNull { it.id == state.currentGroupId } ?: catalog.groups.firstOrNull()
                Column(Modifier.weight(1f).padding(start = WishlistTokens.Space.s8, top = WishlistTokens.Space.s16), verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s16)) {
                    group?.let { g ->
                        WLText(g.name, CategoryCaption, color = c.textSecondary)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8), verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8)) {
                            g.categories.forEach { entry ->
                                WLChip(entry.name, selected = entry.id == state.selectedId, onClick = {
                                    picker.select(entry.id)
                                    overlay.dismiss()
                                })
                            }
                            WLAddChip(stringResource(R.string.category_picker_add_chip), onClick = {
                                form.startCreate(g.id, FormSource.PICKER, catalog)
                                page.creating = true
                            })
                        }
                    }
                }
            }
        }
    }
}
