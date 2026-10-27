package app.wishlist.android.feature.purpose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.wishlist.android.R
import app.wishlist.android.WishlistApplication
import app.wishlist.android.designsystem.*
import app.wishlist.android.designsystem.overlay.*
import app.wishlist.android.feature.category.*
import app.wishlist.android.feature.detail.DetailStatusBlock
import app.wishlist.android.navigation.*
import app.wishlist.android.ui.ItemDetailRoute
import app.wishlist.shared.presentation.PurposeFormPresenter
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
internal fun PurposeDetailScreen(route: PurposeDetailRoute) {
    val nav = LocalWLNavigator.current
    val overlay = LocalOverlayHostState.current
    val store = LocalWLEntryViewModelStoreOwner.current
    val runtime = (LocalContext.current.applicationContext as WishlistApplication).runtime
    val owner = remember(store) { PurposePresenterOwner.of(store, "purpose.detail") { runtime.purposeDetailPresenter() } }
    val forms = remember(store) { CategoryFormSlot.of(store, "purpose.detail.form") { runtime.purposeFormPresenter() } }
    val presenter = owner.presenter
    val state by presenter.state.collectAsState()
    val scroll = rememberScrollState()
    val anchors = remember { ListAnchorController(ListAnchorTracker(), scroll) }
    // C7: coming back (pop, tab) refreshes around the card that was first; the app returning, around the one shown now.
    LaunchedEffect(owner) { runtime.ready.first { it }; owner.enter({ it.load(route.purposeId) }, { it.refresh(anchors.keep(owner.lastAnchor)) }) }
    LaunchedEffect(anchors) {
        snapshotFlow { anchors.tracker.firstVisible(scroll.value) }.collect { if (it != null) owner.lastAnchor = it }
    }
    var resumes by remember(owner) { mutableIntStateOf(0) }
    LifecycleResumeEffect(owner) {
        if (resumes++ > 0 && runtime.ready.value) presenter.foreground(anchors.capture())
        onPauseOrDispose { }
    }
    var restoreEditing by rememberSaveable { mutableStateOf(false) }
    var form by remember { mutableStateOf(forms.current.takeIf { restoreEditing }) }
    SideEffect { restoreEditing = form != null }
    val editing = form
    val formState = editing?.state?.collectAsState()?.value
    val sheet = rememberWLHeaderSheetState()
    val scope = rememberCoroutineScope()
    val endEditing = {
        form?.state?.value?.recoveredDetail?.let {
            presenter.applyEdited(it)
            PurposeChanges.changed()
        }
        form = null
    }
    val discard = rememberDiscardSpec { endEditing() }
    val cancelEdit = {
        val s = editing?.state?.value
        when {
            s?.submitting == true -> Unit
            s?.dirty == true -> { overlay.showDialog(discard); Unit }
            else -> { endEditing(); Unit }
        }
    }
    BackHandler(enabled = editing != null && !overlay.isShowing && nav.currentTab == WLTab.Purpose && !nav.isTransitioning && nav.stack(WLTab.Purpose).lastOrNull() == route) { cancelEdit() }
    LaunchedEffect(editing, formState?.result) {
        formState?.result?.let { presenter.applyEdited(it); PurposeChanges.changed(); form = null }
    }
    LaunchedEffect(formState?.gone) { if (formState?.gone == true) { form = null; presenter.refresh() } }
    val latest by rememberUpdatedState(state)
    LaunchedEffect(scroll) {
        snapshotFlow { val s = latest; shouldLoadMore(scroll.value, scroll.maxValue, s.hasMore, s.itemsLoading || s.appending || s.loading, s.appendFailed) }
            .distinctUntilChanged().collect { if (it) presenter.loadMore() }
    }
    ListAnchorEffects(
        anchors, state.restore, state.hasNewer, busy = state.loading || state.itemsLoading || state.prepending, prepending = state.prepending,
        applied = owner.appliedRestore, onApplied = { owner.appliedRestore = it }, loadNewer = presenter::loadNewer,
        keepPositionOnNullRestore = true, // Ruling 8: the header sheet and list stay put
    )
    val detail = state.detail
    when {
        state.gone -> DetailStatusBlock(stringResource(R.string.purpose_gone), stringResource(R.string.detail_close), { nav.pop() })
        detail == null && state.error != null -> DetailStatusBlock(stringResource(R.string.purpose_failed), stringResource(R.string.detail_retry), presenter::retry)
        detail == null -> Box(Modifier.fillMaxSize().background(LocalWLColors.current.background))
        else -> {
            val anchor = rememberWLMenuAnchor()
            val editLabel = stringResource(R.string.category_menu_edit)
            val deleteLabel = stringResource(R.string.purpose_delete)
            val openMenu = {
                anchor.boundsInWindow()?.let { bounds ->
                    overlay.showMenu(bounds, listOf(
                        WLMenuItem(editLabel, icon = { WLIcon(WLLineIcon.Pen) }, enabled = "EDIT" in detail.allowedActions) {
                            scope.launch {
                                scroll.scrollTo(0)
                                sheet.animateTo(WLSheetDetent.Resting)
                                form = forms.fresh().also { it.startEdit(detail) }
                            }
                        },
                        WLMenuItem(deleteLabel, icon = { WLIcon(WLLineIcon.Trash) }, enabled = false) {},
                    ))
                }
                Unit
            }
            WLHeaderSheet(sheet, expandedTop = wlTopBarBottom(), scrollState = scroll,
                modifier = Modifier.background(purposeColor(formState?.colorKey ?: detail.colorKey)),
                header = { progress ->
                    PurposeHeader(detail, editing, formState, progress,
                        onBack = { if (editing != null) cancelEdit() else nav.pop() },
                        onMenu = openMenu, menuModifier = Modifier.wlAnchor(anchor), onCancel = cancelEdit)
                },
            ) {
                Column(anchors.tracker.contentModifier) { WLOnSheet {
                    Column(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.items.isNotEmpty()) WLText(stringResource(R.string.purpose_candidates, detail.candidateCount), WLType.button, Modifier.padding(horizontal = 4.dp))
                        when {
                            state.itemsError != null && state.items.isEmpty() -> DetailStatusBlock(stringResource(R.string.purpose_failed), stringResource(R.string.detail_retry), presenter::retry)
                            state.items.isEmpty() && !state.itemsLoading -> Box(Modifier.fillMaxWidth().height(400.dp), contentAlignment = Alignment.Center) {
                                EmptyState(stringResource(R.string.purpose_candidates_empty), keepAllBreaks(stringResource(R.string.purpose_candidates_empty_body))) { WLIcon(WLLineIcon.Purpose, size = 24.dp) }
                            }
                            else -> CategoryGrid(state.items, state.itemsLoading && state.items.isEmpty(),
                                onOpen = { nav.push(ItemDetailRoute(it.id), "purpose/${detail.id}/${it.id}") }, modifier = Modifier.fillMaxWidth(), merchantMeta = true,
                                cardTitle = { item -> purposeCandidateTitleFallback(item.product.name, item.analysis.status)?.let { stringResource(it) } },
                                anchors = anchors.tracker)
                        }
                        if (state.error != null || (state.itemsError != null && state.items.isNotEmpty())) {
                            WLText(stringResource(R.string.purpose_failed), WLType.label, color = LocalWLColors.current.textSecondary)
                        }
                        if (state.appendFailed) WLButton(stringResource(R.string.detail_retry), WLButtonKind.Secondary, presenter::retry)
                        WLButton(stringResource(R.string.purpose_refresh), WLButtonKind.Secondary, { presenter.refresh(anchors.capture()) }, enabled = !state.loading && !state.itemsLoading)
                        Spacer(Modifier.height(tabBarClearance()))
                    }
                } }
            }
        }
    }
}
