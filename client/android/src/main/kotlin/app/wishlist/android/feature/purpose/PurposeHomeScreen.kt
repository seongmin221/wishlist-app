package app.wishlist.android.feature.purpose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.wishlist.android.R
import app.wishlist.android.WishlistApplication
import app.wishlist.android.designsystem.*
import app.wishlist.android.designsystem.overlay.LocalOverlayHostState
import app.wishlist.android.feature.category.CategoryFormSlot
import app.wishlist.android.feature.category.rememberDiscardSpec
import app.wishlist.android.feature.category.tabBarClearance
import app.wishlist.android.feature.category.keepAllBreaks
import app.wishlist.android.feature.detail.ProductPhoto
import app.wishlist.android.feature.detail.DetailStatusBlock
import app.wishlist.android.navigation.*
import app.wishlist.android.ui.LoginRoute
import app.wishlist.shared.model.PurposeSummary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PurposeHomeScreen() {
    val c = LocalWLColors.current
    val nav = LocalWLNavigator.current
    val overlay = LocalOverlayHostState.current
    val store = LocalWLEntryViewModelStoreOwner.current
    val runtime = (LocalContext.current.applicationContext as WishlistApplication).runtime
    val owner = remember(store) { PurposePresenterOwner.of(store, "purpose.home") { runtime.purposeHomePresenter() } }
    val forms = remember(store) { CategoryFormSlot.of(store, "purpose.home.form") { runtime.purposeFormPresenter() } }
    val presenter = owner.presenter
    val state by presenter.state.collectAsState()
    LaunchedEffect(owner) { runtime.ready.first { it }; owner.enter({ it.load() }, { it.refresh() }) }
    var resumes by remember(owner) { mutableIntStateOf(0) }
    LifecycleResumeEffect(owner) {
        if (resumes++ > 0 && runtime.ready.value) presenter.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(owner) {
        PurposeChanges.revision.collect { if (owner.markSeen(it)) presenter.refresh() }
    }
    val discard = rememberDiscardSpec { overlay.dismissAll() }
    val create = {
        overlay.showPurposeCreate(forms.fresh(), discard) { detail ->
            presenter.refresh()
            nav.push(PurposeDetailRoute(detail.id), "purpose/created/${detail.id}")
        }
        Unit
    }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = expandedPurposeId(expanded, state.purposes.map { it.id })
    LaunchedEffect(shown) { expanded = shown }
    val scroll = rememberScrollState()
    WLScrollToTopEffect(WLTab.Purpose, scroll)
    var pulling by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(c.background)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            WLTopBar(sideMargin = 0.dp, title = { WLText(stringResource(R.string.purpose_title), WLType.display28) })
            if (state.signedIn) WLText(stringResource(R.string.purpose_summary, state.activeCount), WLType.label, color = c.textSecondary)
        }
        when {
            !state.signedIn -> PurposeCenter {
                EmptyState(stringResource(R.string.purpose_title), keepAllBreaks(stringResource(R.string.purpose_signed_out))) { WLIcon(WLLineIcon.Purpose, size = 24.dp) }
                WLButton(stringResource(R.string.category_signed_out_action), WLButtonKind.Primary, { nav.push(LoginRoute, "purpose/login") })
            }
            state.purposes.isEmpty() && state.error != null -> PurposeCenter {
                DetailStatusBlock(stringResource(R.string.purpose_failed), stringResource(R.string.detail_retry), presenter::retry)
            }
            else -> PullToRefreshBox(pulling, {
                pulling = true
                scope.launch { try { presenter.refreshNow() } finally { pulling = false } }
            }, Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    if (state.loaded && state.purposes.isEmpty()) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                            EmptyState(stringResource(R.string.purpose_empty_title), keepAllBreaks(stringResource(R.string.purpose_empty_body))) { WLIcon(WLLineIcon.Purpose) }
                        }
                    }
                    PurposeStack(Modifier.padding(horizontal = 16.dp)) {
                        state.purposes.forEach { purpose -> key(purpose.id) {
                            PurposeCard(purpose, purpose.id == shown) {
                                if (purpose.id == shown) nav.push(PurposeDetailRoute(purpose.id), "purpose/${purpose.id}") else expanded = purpose.id
                            }
                        } }
                    }
                    if (state.loaded) {
                        PurposeAddButton(stringResource(R.string.purpose_add), create,
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp), enabled = state.activeCount < state.purposeLimit)
                        WLButton(stringResource(R.string.purpose_archive, state.archiveCount), WLButtonKind.Secondary, {},
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp), enabled = false)
                    }
                    if (state.refreshFailed) WLText(stringResource(R.string.purpose_failed), WLType.label, Modifier.padding(20.dp), color = c.textSecondary)
                    Spacer(Modifier.height(tabBarClearance()))
                }
            }
        }
    }
}

@Composable
private fun PurposeCenter(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp).padding(bottom = tabBarClearance()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically), content = content)
}

@Composable
private fun PurposeStack(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier.fillMaxWidth()) { children, constraints ->
        val overlap = 22.dp.roundToPx()
        val measured = children.map { it.measure(constraints.copy(minHeight = 0)) }
        layout(constraints.maxWidth, (measured.sumOf { it.height } - overlap * (measured.size - 1).coerceAtLeast(0)).coerceAtLeast(0)) {
            var y = 0
            measured.forEach { it.place(0, y); y += it.height - overlap }
        }
    }
}

@Composable
private fun PurposeCard(p: PurposeSummary, expanded: Boolean, onClick: () -> Unit) {
    val c = LocalWLColors.current
    val shape = RoundedCornerShape(28.dp)
    val spec = tween<IntSize>(300, easing = WishlistTokens.Curve.emphasized)
    val activityText = purposeActivityText(p.activity)
    Column(Modifier.fillMaxWidth().clip(shape).background(purposeColor(p.colorKey)).border(2.dp, c.background, shape).clickable(role = Role.Button, onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 20.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WLIconTile(size = 36.dp, radius = 12.dp) { WLIcon(purposeIcon(p.iconKey), size = 18.dp) }
            WLText(p.name, WLType.display28, Modifier.weight(1f), color = WishlistTokens.Purpose.onPurpose, maxLines = 1, overflow = TextOverflow.Ellipsis)
            WLText(p.candidateCount.toString(), WLType.price.copy(fontSize = 26.sp), color = WishlistTokens.Purpose.onPurpose)
        }
        AnimatedVisibility(!expanded, enter = expandVertically(spec), exit = shrinkVertically(spec)) { Spacer(Modifier.height(16.dp)) }
        AnimatedVisibility(expanded, enter = expandVertically(spec), exit = shrinkVertically(spec)) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                WLText("${stringResource(R.string.purpose_candidates, p.candidateCount)} · $activityText", WLType.label, color = WishlistTokens.Purpose.onPurpose)
                if (p.previews.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    p.previews.take(4).forEach { preview ->
                        ProductPhoto(preview.imageUrl, Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(14.dp)))
                    }
                    repeat((4 - p.previews.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun PurposeAddButton(text: String, onClick: () -> Unit, modifier: Modifier, enabled: Boolean) {
    val c = LocalWLColors.current
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(28.dp))
        .drawBehind {
            val stroke = 1.5.dp.toPx()
            drawRoundRect(c.textSecondary, topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(28.dp.toPx()),
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
        }.clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        WLIcon(WLLineIcon.Plus, size = 18.dp)
        WLText(text, WLType.button)
    }
}
