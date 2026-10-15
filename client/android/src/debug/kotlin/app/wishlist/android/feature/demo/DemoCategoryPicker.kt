package app.wishlist.android.feature.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.wishlist.android.WishlistApplication
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.WLButton
import app.wishlist.android.designsystem.WLButtonKind
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.overlay.LocalOverlayHostState
import app.wishlist.android.feature.category.CategoryPresenterOwner
import app.wishlist.android.feature.category.PickerPage
import app.wishlist.android.feature.category.rememberDiscardSpec
import app.wishlist.android.feature.category.showCategoryPicker
import app.wishlist.android.navigation.LocalWLEntryViewModelStoreOwner

/**
 * DEBUG only (C5 D1): opens the category picker sheet (FProductCategoryPicker/Create) without the C8
 * product edit, and shows what it picked. Lives at the end of the purpose tab demo.
 */
@Composable
internal fun DemoCategoryPickerEntry() {
    val overlay = LocalOverlayHostState.current
    val storeOwner = LocalWLEntryViewModelStoreOwner.current
    val runtime = (LocalContext.current.applicationContext as WishlistApplication).runtime
    val picker = remember(storeOwner) { CategoryPresenterOwner.of(storeOwner, "demo.picker") { runtime.categoryPickerPresenter() } }.presenter
    val forms = remember(storeOwner) { app.wishlist.android.feature.category.CategoryFormSlot.of(storeOwner, "demo.picker.form") { runtime.categoryFormPresenter() } }
    val page = remember { PickerPage() }
    val state by picker.state.collectAsState()
    var picked by remember { mutableStateOf<String?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.result) {
        val result = state.result ?: return@LaunchedEffect
        picked = "${result.name} (${result.id})"
        selectedId = result.id
        picker.consumeResult()
    }
    val discardToChips = rememberDiscardSpec { page.creating = false }
    val discardAndClose = rememberDiscardSpec { overlay.dismissAll() }
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WLText("DEBUG · 카테고리 선택 시트", DemoCaption, color = LocalWLColors.current.textSecondary)
        WLButton("카테고리 선택 시트 열기", WLButtonKind.Secondary, onClick = {
            overlay.showCategoryPicker(picker, forms, selectedId, page, discardToChips, discardAndClose)
        })
        picked?.let { WLText("고른 카테고리: $it", DemoCaption) }
    }
}
