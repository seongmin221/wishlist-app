package app.wishlist.android.feature.category

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.wishlist.android.R
import app.wishlist.android.WishlistApplication
import app.wishlist.android.designsystem.EmptyState
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.Masonry2Col
import app.wishlist.android.designsystem.WLButton
import app.wishlist.android.designsystem.WLButtonKind
import app.wishlist.android.designsystem.WLCircleButton
import app.wishlist.android.designsystem.WLIcon
import app.wishlist.android.designsystem.WLLineIcon
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.WLTopBar
import app.wishlist.android.designsystem.WLType
import app.wishlist.android.designsystem.WishlistTokens
import app.wishlist.android.designsystem.overlay.LocalOverlayHostState
import app.wishlist.android.designsystem.overlay.WLMenuItem
import app.wishlist.android.designsystem.overlay.rememberWLMenuAnchor
import app.wishlist.android.designsystem.overlay.wlAnchor
import app.wishlist.android.feature.detail.DetailNoticeLine
import app.wishlist.android.feature.detail.DetailStatusBlock
import app.wishlist.android.feature.detail.DetailText
import app.wishlist.android.feature.detail.PhotoSource
import app.wishlist.android.feature.detail.deviceUtcOffsetSeconds
import app.wishlist.android.feature.detail.photoSourceFor
import app.wishlist.android.feature.detail.rememberBriefNotice
import app.wishlist.android.feature.detail.rememberMinuteClock
import app.wishlist.android.navigation.LocalPhotoHandoff
import app.wishlist.android.navigation.LocalWLEntryViewModelStoreOwner
import app.wishlist.android.navigation.PhotoTransitionDebug
import app.wishlist.android.navigation.LocalWLNavigator
import app.wishlist.android.navigation.WLScrollToTopEffect
import app.wishlist.android.navigation.WLTab
import app.wishlist.android.ui.ItemDetailRoute
import app.wishlist.shared.domain.cardMetaLine
import app.wishlist.shared.domain.isUnconfirmed
import app.wishlist.shared.model.CategoryKind
import app.wishlist.shared.model.WishlistItem
import app.wishlist.shared.presentation.CategoryFormPresenter
import app.wishlist.shared.presentation.CategoryListPresenter
import app.wishlist.shared.presentation.FormResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The card's key (tab-unique): the detail's photo shared element and its held photo follow it. */
internal fun cardSourceKey(categoryId: String, itemId: String) = "category/$categoryId/$itemId"

/** How close to the end (px of scroll) the next page is asked for. */
private const val LOAD_MORE_DISTANCE_PX = 1500

/**
 * Whether to ask for the next page (pure): near the end — including a list shorter than the screen
 * (`maxValue` 0) — with a page left, nothing in flight, and no failed append (that one waits for 다시 시도).
 */
internal fun shouldLoadMore(scrollValue: Int, scrollMax: Int, hasMore: Boolean, busy: Boolean, appendFailed: Boolean): Boolean =
    hasMore && !busy && !appendFailed && scrollValue >= scrollMax - LOAD_MORE_DISTANCE_PX

/**
 * 세부 유형 목록(FCategoryList · ListCustom · ListCustomEmpty, C5 spec §3). 위에 붙은 머리(뒤로 · 이름 + 개수 · 상위 이름,
 * 직접 만든 것은 ⋯ "편집"만, D7) + 2열 엇갈림 카드. 사진은 페이지 단위 skeleton 뒤 원래 비율로 한 번에 놓는다(D10).
 * - 끝 근처에서 다음 페이지, 실패하면 끝에 "더 불러오지 못했어요 · 다시 시도".
 * - C7 목록 anchor: 당겨서 새로고침·foreground(두 번째 이후 resume)는 첫 보이는 카드 id로 그 카드 주변 window를 받아
 *   전체를 바꾸고, `restore`로 그 카드(같은 카드면 카드 안 위치까지)를 첫 카드로 되돌린다. 위쪽 끝에 닿으면 `loadNewer`.
 * - 편집 성공 → 이름을 바로 바꾸고 머리만 다시 받는다(목록·위치 유지). 없어진 custom → "삭제된 카테고리예요" + 닫기.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoryListScreen(route: CategoryListRoute) {
    val c = LocalWLColors.current
    val nav = LocalWLNavigator.current
    val overlay = LocalOverlayHostState.current
    val storeOwner = LocalWLEntryViewModelStoreOwner.current
    val context = LocalContext.current
    val runtime = (context.applicationContext as WishlistApplication).runtime
    val owner = remember(storeOwner) { CategoryPresenterOwner.of(storeOwner, "category.list") { runtime.categoryListPresenter() } }
    val formSlot = remember(storeOwner) { CategoryFormSlot.of(storeOwner, "category.list.form") { runtime.categoryFormPresenter() } }
    val list: CategoryListPresenter = owner.presenter
    val photoHandoff = LocalPhotoHandoff.current
    val state by list.state.collectAsState()

    LaunchedEffect(owner) {
        runtime.ready.first { it }
        owner.once { it.load(route.hint) }
    }

    val scroll = rememberScrollState()
    WLScrollToTopEffect(WLTab.Category, scroll)
    val anchors = remember { ListAnchorController(ListAnchorTracker(), scroll) }
    var resumes by remember(owner) { mutableIntStateOf(0) }
    LifecycleResumeEffect(owner) {
        if (resumes++ > 0 && runtime.ready.value) list.foreground(anchors.capture())
        onPauseOrDispose { }
    }
    val partialNotices = remember(owner) { mutableIntStateOf(0) }
    LaunchedEffect(state.partialFailed) { if (state.partialFailed) partialNotices.intValue++ }
    val notice = rememberBriefNotice(partialNotices.intValue.takeIf { it > 0 }, stringResource(R.string.category_partial_failed))

    val discard = rememberDiscardSpec { overlay.dismissAll() }
    val anchor = rememberWLMenuAnchor()
    val editText = stringResource(R.string.category_menu_edit)
    val header = state.header
    val kind = header?.kind ?: route.hint.kind
    val openEdit = {
        overlay.showCategoryForm(formSlot.fresh(), discard, start = { it.startEdit(route.hint.categoryId, header?.parentName ?: route.hint.parentName) }) { result ->
            when (result) {
                is FormResult.Saved -> {
                    list.applyEdited(result.detail)
                    CategoryChanges.changed() // the first screen's chip shows the new name
                }
                FormResult.Gone -> list.refreshHeader() // CAT-02 404 → gone
                is FormResult.Created -> Unit
            }
        }
        Unit
    }

    Box(Modifier.fillMaxSize().background(c.background)) {
        Column(Modifier.fillMaxSize()) {
            WLTopBar(
                background = c.background,
                leading = { WLCircleButton(WLLineIcon.Back, stringResource(R.string.category_back), onClick = { nav.pop() }) },
                trailing = if (kind == CategoryKind.CUSTOM && !state.gone) {
                    {
                        WLCircleButton(
                            WLLineIcon.More, stringResource(R.string.category_list_more),
                            onClick = {
                                anchor.boundsInWindow()?.let { bounds ->
                                    overlay.showMenu(bounds, listOf(WLMenuItem(editText, icon = { WLIcon(WLLineIcon.Pen, size = 18.dp) }) { openEdit() }))
                                }
                            },
                            modifier = Modifier.wlAnchor(anchor),
                        )
                    }
                } else null,
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s4)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8)) {
                            WLText(header?.name ?: route.hint.name, WLType.title.copy(fontSize = 22.sp), Modifier.weight(1f, fill = false).alignByBaseline(),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            header?.count?.let { WLText(it.toString(), ListCountStyle, Modifier.alignByBaseline(), color = c.textSecondary, maxLines = 1) }
                        }
                        val parent = header?.parentName ?: route.hint.parentName
                        val subtitle = CategoryText.listSubtitle(kind)?.let { stringResource(it, parent) } ?: parent
                        WLText(subtitle, CategoryCaption, color = c.textSecondary, maxLines = 1)
                    }
                },
            )
            when {
                state.gone -> DetailStatusBlock(stringResource(R.string.category_gone), stringResource(R.string.detail_close), { nav.pop() })
                state.items.isEmpty() && state.itemsError != null ->
                    DetailStatusBlock(stringResource(DetailText.errorText(state.itemsError!!)), stringResource(R.string.detail_retry), list::retry)
                state.items.isEmpty() && !state.loading && state.totalCount != null -> Box(
                    Modifier.fillMaxSize().padding(bottom = tabBarClearance()), contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        stringResource(R.string.category_empty_title),
                        if (kind == CategoryKind.CUSTOM) keepAllBreaks(stringResource(R.string.category_list_empty_body)) else "",
                    ) { WLIcon(WLLineIcon.Apps, size = 24.dp) }
                }
                else -> PullToRefreshBox(
                    isRefreshing = state.loading && state.items.isNotEmpty(),
                    onRefresh = { list.refresh(anchors.capture()) },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Column(Modifier.fillMaxSize().verticalScroll(scroll).then(anchors.tracker.contentModifier)) {
                        CategoryGrid(
                            items = state.items,
                            loading = state.loading && state.items.isEmpty(),
                            sourceKeyOf = { item -> cardSourceKey(route.hint.categoryId, item.id) },
                            onOpen = { item, shown ->
                                // Card photo → detail photo (C5 PR B): the image the card draws travels with the push.
                                PhotoTransitionDebug.onPhotoTransitionStart(context)
                                if (nav.push(ItemDetailRoute(item.id, viaPhoto = true), cardSourceKey(route.hint.categoryId, item.id)) && shown != null) {
                                    photoHandoff.hold(nav.entries(nav.currentTab).last().id, shown.image, shown.memoryCacheKey, shown.url)
                                }
                            },
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = WishlistTokens.Space.s8),
                            anchors = anchors.tracker,
                        )
                        if (state.appendFailed) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = WishlistTokens.Space.s16),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s12),
                            ) {
                                WLText(stringResource(R.string.category_load_more_failed), WLType.body, Modifier.weight(1f), color = c.textSecondary)
                                WLButton(stringResource(R.string.detail_retry), WLButtonKind.Secondary, onClick = list::retry)
                            }
                        }
                        Spacer(Modifier.height(tabBarClearance()))
                    }
                    // Near the end (or a first page that does not fill the screen): the next page. Re-evaluated on
                    // every list state change too, so a request the Presenter ignored (busy) comes back once it is free.
                    val latest by androidx.compose.runtime.rememberUpdatedState(state)
                    LaunchedEffect(scroll) {
                        snapshotFlow {
                            val s = latest
                            shouldLoadMore(scroll.value, scroll.maxValue, s.hasMore, s.appending || s.loading, s.appendFailed)
                        }.distinctUntilChanged().collect { if (it) list.loadMore() }
                    }
                    ListAnchorEffects(
                        anchors, state.restore, state.hasNewer, busy = state.loading || state.prepending, prepending = state.prepending,
                        applied = owner.appliedRestore, onApplied = { owner.appliedRestore = it }, loadNewer = list::loadNewer,
                    )
                }
            }
        }
        DetailNoticeLine(notice, Modifier.align(Alignment.TopCenter))
    }
}

/**
 * 2열 엇갈림(열 12, 카드 20). Each page (one ITEM-02 answer) is a skeleton until [PhotoPagePlanner] lays
 * it out; laid-out cards keep their height. A refresh (the list restarts) forgets the pages.
 */
@Composable
internal fun CategoryGrid(
    items: List<WishlistItem>,
    loading: Boolean,
    onOpen: (WishlistItem, ShownPhoto?) -> Unit,
    modifier: Modifier,
    merchantMeta: Boolean = false,
    cardTitle: @Composable (WishlistItem) -> String? = { null },
    anchors: ListAnchorTracker? = null,
    sourceKeyOf: ((WishlistItem) -> String)? = null,
) {
    val context = LocalContext.current
    // The layout and photo sizes outlive this composition (the list leaves it while its detail is shown).
    val grid = CategoryGridHolder.of(LocalWLEntryViewModelStoreOwner.current)
    val book = remember(grid) { CategoryPhotoBook(context.applicationContext, grid.sizes) }
    val now = rememberMinuteClock()

    // Composed again with the same items (back from the detail), the layout stays as it was.
    LaunchedEffect(items) {
        val ids = items.map { it.id }
        grid.onItems(ids, items.associate { it.id to categoryPhotoUrl(it) })
        anchors?.reorder(ids)
    }

    val shown = grid.layout
    val ratios = shown.ratios
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columnPx = with(LocalDensity.current) { ((maxWidth - WishlistTokens.Space.s12) / 2).roundToPx() }
        val byId = items.associateBy { it.id }
        // Measure every page's photos (six at a time) and lay the page out when ready or after 3 s.
        shown.pages.forEachIndexed { index, page -> key(shown, index, page.firstOrNull()) {
            LaunchedEffect(shown, page, columnPx) {
                val pageItems = page.mapNotNull { byId[it] }
                val cards = pageItems.map { PhotoPagePlanner.Card(it.id, photoSourceFor(it.product.imageUrl) is PhotoSource.Remote) }
                val started = System.currentTimeMillis()
                launch {
                    coroutineScope { pageItems.map { item -> async { book.measure(item, columnPx) } }.awaitAll() }
                }
                shown.settlePage(index, cards, { book.photosFor(pageItems) }, { System.currentTimeMillis() - started })
            }
        } }
        Masonry2Col(Modifier.fillMaxWidth(), gap = WishlistTokens.Space.s12, verticalGap = WishlistTokens.Space.s20) {
            if (loading) repeat(4) { CategorySkeletonCard() }
            items.forEach { item -> key(item.id) {
                val ratio = ratios[item.id]
                if (ratio == null) {
                    CategorySkeletonCard()
                } else {
                    val url = (photoSourceFor(item.product.imageUrl) as? PhotoSource.Remote)?.url
                    // C7: only laid-out cards report their place (a skeleton's height is not final).
                    Box(Modifier.anchorCard(anchors, item.id)) {
                        CategoryCard(
                            item = item,
                            ratio = ratio,
                            meta = cardMetaLine(item, now, ::deviceUtcOffsetSeconds),
                            unconfirmed = isUnconfirmed(item),
                            photoRequest = url?.let { remember(it, columnPx) { book.request(it, columnPx) } },
                            onClick = { photo -> onOpen(item, photo) },
                            merchantMeta = merchantMeta,
                            title = cardTitle(item) ?: item.product.name.orEmpty(),
                            sourceKey = sourceKeyOf?.invoke(item),
                        )
                    }
                }
            } }
        }
    }
}
