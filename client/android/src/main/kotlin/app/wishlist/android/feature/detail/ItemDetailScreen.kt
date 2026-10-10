package app.wishlist.android.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.wishlist.android.R
import app.wishlist.android.WishlistApplication
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.LocalWLDark
import app.wishlist.android.designsystem.formatPrice
import app.wishlist.android.designsystem.PriceText
import app.wishlist.android.designsystem.PurposeDot
import app.wishlist.android.designsystem.WLIcon
import app.wishlist.android.designsystem.WLIconTile
import app.wishlist.android.designsystem.WLLineIcon
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.WLType
import app.wishlist.android.designsystem.WishlistTokens
import app.wishlist.android.feature.home.resolve
import app.wishlist.android.navigation.LocalWLEntryViewModelStoreOwner
import app.wishlist.android.navigation.LocalWLNavigator
import app.wishlist.android.ui.ItemDetailRoute
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.domain.DetailKind
import app.wishlist.shared.domain.DetailKinds
import app.wishlist.shared.domain.DisplayFormat
import app.wishlist.shared.model.WishlistItem
import app.wishlist.shared.presentation.ItemDetailState
import kotlinx.coroutines.delay
import java.util.TimeZone
import kotlin.time.Clock
import kotlin.time.Instant

/** The device's UTC offset (seconds) at [at], for the shared calendar-date labels. */
internal fun deviceUtcOffsetSeconds(at: Instant): Int = TimeZone.getDefault().getOffset(at.toEpochMilliseconds()) / 1000

/** "now" for relative labels, moved forward every minute while the screen is shown. */
@Composable
internal fun rememberMinuteClock(): Instant {
    val now = remember { mutableStateOf(Clock.System.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now.value = Clock.System.now()
        }
    }
    return now.value
}

/**
 * 서버 상품 상세(C4 spec §3): 분석 중(FProductProcessing)·완료(FProductDetail)·정보 보완 필요를 한 화면이 [DetailKinds]로
 * 고른다. Presenter owner는 이 칸의 ViewModelStore에 있고(pop·replaceTop·계정 떠남에 닫힌다) 처음 한 번 `load`한다.
 * 당겨서 새로고침과 두 번째 이후 resume(다른 앱에서 돌아옴)이 `refresh()`다. ⋯ 메뉴는 C8까지 없다(D2).
 *
 * 상태: 항목 없이 로딩 → 틀만, 항목 없는 오류 → 문구 + 다시 시도(NOT_FOUND는 삭제됨 + 닫기), 항목 있는 오류 → 항목 유지 + 짧은 안내.
 * 항목·로딩·오류가 모두 없는 Initial(계정 세대가 바뀜)이나 로그인 없는 UNAUTHENTICATED(로그아웃 상태로 복원된 스택)는 바로 닫는다.
 */
@Composable
internal fun ItemDetailScreen(itemId: String) {
    val nav = LocalWLNavigator.current
    val storeOwner = LocalWLEntryViewModelStoreOwner.current
    val runtime = (LocalContext.current.applicationContext as WishlistApplication).runtime
    val owner = remember(storeOwner) {
        ViewModelProvider(storeOwner, ItemDetailPresenterOwner.factory(runtime))[ItemDetailPresenterOwner::class.java]
    }
    val state by owner.state.collectAsState()
    val route = remember(itemId) { ItemDetailRoute(itemId) }

    LaunchedEffect(owner) { owner.loadOnce(itemId) }
    // Initial before the first load answer is the load not having started yet; only a later one means the session moved on.
    val seenWork = remember(owner) { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state != ItemDetailState.Initial) seenWork.value = true
        if (shouldClose(state, seenWork.value)) nav.whenSettled(route) { pop() }
    }
    val resumes = remember(owner) { mutableIntStateOf(0) }
    LifecycleResumeEffect(owner) {
        if (resumes.intValue++ > 0) owner.refresh()
        onPauseOrDispose { }
    }

    val item = state.item
    val error = state.error
    // Each refresh that fails while an item is shown brings the short line back.
    val failures = remember(owner) { mutableIntStateOf(0) }
    LaunchedEffect(state) { if (owner.takeRefreshNotice(state)) failures.intValue++ }
    val notice = rememberBriefNotice(failures.intValue.takeIf { it > 0 }, error?.let { stringResource(DetailText.errorText(it)) }.orEmpty())
    val close = { nav.pop(); Unit }
    val kind = item?.let { DetailKinds.of(it) }

    DetailScaffold(
        onBack = close,
        originalUrl = item?.sourceUrl?.takeIf { kind?.kind != DetailKind.GONE },
        notice = notice,
        refresh = if (item != null && kind?.kind != DetailKind.GONE) DetailRefresh(state.loading, owner::refresh) else null,
    ) {
        when {
            item == null && error != null -> if (error.kind == ErrorKind.NOT_FOUND) {
                DetailStatusBlock(stringResource(R.string.detail_not_found), stringResource(R.string.detail_close), close)
            } else {
                DetailStatusBlock(stringResource(DetailText.errorText(error)), stringResource(R.string.detail_retry), owner::retry)
            }
            item == null -> PhotoFrame { }
            kind?.kind == DetailKind.GONE ->
                DetailStatusBlock(stringResource(R.string.detail_not_found), stringResource(R.string.detail_close), close)
            kind?.kind == DetailKind.PROCESSING -> ProcessingContent(item)
            else -> ProductContent(item, kind?.notice?.let { DetailText.noticeText(it) })
        }
    }
}

/** Initial after work started (the account generation changed) or signed out with nothing shown: the screen closes. */
internal fun shouldClose(state: ItemDetailState, seenWork: Boolean): Boolean =
    (seenWork && state == ItemDetailState.Initial) ||
        (state.item == null && !state.loading && state.error?.kind == ErrorKind.UNAUTHENTICATED)

/** 사진 칸: 좌우 20, 정사각, 모서리 20, 카드색. */
@Composable
private fun PhotoFrame(content: @Composable () -> Unit) {
    Box(
        Modifier
            .padding(horizontal = WishlistTokens.Space.screenMargin)
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(WishlistTokens.Radius.m))
            .background(LocalWLColors.current.card),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** FProductDetail: 사진 → (안내) 브랜드·이름·가격 묶음 → 정보 카드 → 저장 시점(간격 20). */
@Composable
private fun ProductContent(item: WishlistItem, noticeRes: Int?) {
    val c = LocalWLColors.current
    val now = rememberMinuteClock()
    // 2026-10-04: 1:1 칸(모서리 20) 안 여백 56에 사진을 원래 비율로(보드 FProductDetail). 자리표시는 칸 전체.
    PhotoFrame { ProductPhoto(item.product.imageUrl, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, imagePadding = 56.dp) }
    Column(
        Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp),
        verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s20),
    ) {
        if (noticeRes != null) NoticeCard(stringResource(noticeRes), DisplayFormat.host(item.sourceUrl))
        Column {
            item.product.brand?.takeIf { it.isNotBlank() }?.let {
                WLText(it, DetailStyles.brand, maxLines = 1)
                Spacer(Modifier.height(WishlistTokens.Space.s8))
            }
            val name = item.product.name?.takeIf { it.isNotBlank() }
            if (name != null) {
                WLText(name, DetailStyles.name)
            } else {
                WLText(DisplayFormat.host(item.sourceUrl), DetailStyles.name)
                Spacer(Modifier.height(WishlistTokens.Space.s4))
                WLText(stringResource(R.string.detail_name_empty), DetailStyles.note, color = c.textSecondary)
            }
            val price = item.product.price?.canonical
            if (price != null && formatPrice(price, item.product.currency) != null) {
                Spacer(Modifier.height(10.dp))
                PriceText(price, item.product.currency, style = DetailStyles.price)
                item.product.metadataCheckedAt?.let { checked ->
                    Spacer(Modifier.height(WishlistTokens.Space.s4))
                    val time = DisplayFormat.relative(checked, now, ::deviceUtcOffsetSeconds)
                    WLText(DetailText.priceCheckedText(time).resolve(), DetailStyles.priceNote, color = c.textSecondary)
                }
            }
        }
        InfoCard(item)
        SavedLine(item, now)
    }
}

/** 정보 보완 안내 한 줄(보드 FProductFill 카드): 실패색 경고 타일 44 + 15/700 문장 + 12 host. */
@Composable
private fun NoticeCard(text: String, host: String) {
    val c = LocalWLColors.current
    val dark = LocalWLDark.current
    val (surface, icon) = if (dark) WishlistTokens.Status.Dark.failedSurface to WishlistTokens.Status.Dark.failedIcon else WishlistTokens.Status.Light.failedSurface to WishlistTokens.Status.Light.failedIcon
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(WishlistTokens.Radius.l)).background(c.card).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WLIconTile(color = surface) { WLIcon(WLLineIcon.Warning, color = icon) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s4)) {
            WLText(text, DetailStyles.infoValue)
            WLText(host, WLType.label, color = c.textSecondary, maxLines = 1)
        }
    }
}

/** 정보 카드(모서리 28, 위아래 4): 줄 최소 56·좌우 16, 라벨 폭 60 14 보조색, 값 15/700 오른쪽 정렬. */
@Composable
private fun InfoCard(item: WishlistItem) {
    val c = LocalWLColors.current
    val empty = DetailStyles.infoValue.copy(fontWeight = FontWeight.Normal)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(WishlistTokens.Radius.l)).background(c.card).padding(vertical = WishlistTokens.Space.s4),
    ) {
        InfoRow(stringResource(R.string.detail_category)) {
            val name = item.category.name?.takeIf { it.isNotBlank() }
            if (name != null) {
                WLText(name, DetailStyles.infoValue, textAlign = TextAlign.End)
            } else {
                WLText(stringResource(R.string.detail_category_empty), empty, color = c.textSecondary)
            }
        }
        InfoRow(stringResource(R.string.detail_purpose)) {
            val purpose = item.purpose
            val name = purpose.name?.takeIf { purpose.id != null && it.isNotBlank() }
            if (name != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8)) {
                    PurposeColorDot(purpose.colorKey)
                    WLText(name, DetailStyles.infoValue, textAlign = TextAlign.End)
                }
            } else {
                WLText(stringResource(R.string.detail_purpose_none), empty, color = c.textSecondary)
            }
        }
    }
}

/** 목적 색 점 10: 아는 색은 그 토큰, 모르는 key는 중립(`textSecondary`). */
@Composable
private fun PurposeColorDot(colorKey: String?) {
    val color = DetailText.purposeColor(colorKey)
    if (color != null) {
        PurposeDot(color)
    } else {
        Box(Modifier.size(10.dp).background(LocalWLColors.current.textSecondary, CircleShape))
    }
}

@Composable
private fun InfoRow(label: String, value: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = WishlistTokens.Space.s16),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s12),
    ) {
        WLText(label, WLType.body, Modifier.width(60.dp), color = LocalWLColors.current.textSecondary, maxLines = 1)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { value() }
    }
}

@Composable
private fun SavedLine(item: WishlistItem, now: Instant) {
    val label = DisplayFormat.saved(item.savedAt, now, ::deviceUtcOffsetSeconds)
    WLText(DetailText.savedText(label).resolve(), DetailStyles.caption, color = LocalWLColors.current.textSecondary, maxLines = 1)
}

/** FProductProcessing: 높이 222 카드(모서리 28) 안 분석 중 타일 56 + 14 문구, 아래 제목 host·안내·저장 시점(간격 12). */
@Composable
private fun ProcessingContent(item: WishlistItem) {
    val now = rememberMinuteClock()
    val dark = LocalWLDark.current
    val tile = if (dark) WishlistTokens.Status.Dark.analyzingSurface to WishlistTokens.Status.Dark.analyzingIcon else WishlistTokens.Status.Light.analyzingSurface to WishlistTokens.Status.Light.analyzingIcon
    WaitingFrame(WLLineIcon.Sorting, tile, stringResource(R.string.row_processing))
    WaitingBody(DisplayFormat.host(item.sourceUrl)) {
        WLText(stringResource(R.string.detail_processing_note), DetailStyles.note, color = LocalWLColors.current.textSecondary)
        SavedLine(item, now)
    }
}

/** 분석 중·로컬 대기 공통 사진 자리: 좌우 20, 높이 222, 모서리 28, 카드색, 가운데 상태 타일 56(모서리 20) + 14 문구(간격 10). */
@Composable
internal fun WaitingFrame(icon: WLLineIcon, tile: Pair<Color, Color>, caption: String) {
    val c = LocalWLColors.current
    Column(
        Modifier
            .padding(horizontal = WishlistTokens.Space.screenMargin)
            .fillMaxWidth()
            .height(222.dp)
            .clip(RoundedCornerShape(WishlistTokens.Radius.l))
            .background(c.card)
            .then(if (caption.isEmpty()) Modifier else Modifier.semantics(mergeDescendants = true) { contentDescription = caption }),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        WLIconTile(size = 56.dp, radius = WishlistTokens.Radius.m, color = tile.first) { WLIcon(icon, size = 28.dp, color = tile.second) }
        if (caption.isNotEmpty()) {
            WLText(caption, WLType.body, Modifier.padding(horizontal = WishlistTokens.Space.s16), color = c.textSecondary, textAlign = TextAlign.Center)
        }
    }
}

/** 분석 중·로컬 대기 공통 글자 묶음: 위 24·좌우 20, 22/700 제목(host) + 내용(간격 12). */
@Composable
internal fun WaitingBody(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.padding(start = 20.dp, end = 20.dp, top = WishlistTokens.Space.s24),
        verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s12),
    ) {
        WLText(title, DetailStyles.name)
        content()
    }
}
