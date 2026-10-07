package app.wishlist.android.feature.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.Masonry2Col
import app.wishlist.android.designsystem.PriceText
import app.wishlist.android.designsystem.PurposeDot
import app.wishlist.android.designsystem.WLPurposeColor
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.WLTopBar
import app.wishlist.android.designsystem.WLType
import app.wishlist.android.designsystem.WishlistTokens
import app.wishlist.android.navigation.LocalWLNavigator
import app.wishlist.android.navigation.wlSharedPhoto

/**
 * 세부 유형 목록(FCategoryListL): 위에 붙은 머리(`WLTopBar`: 뒤로 + "헤드폰 8" + 상위 이름) + 2열 엇갈림 사진 카드. 탭 바 보임.
 * 머리는 스크롤 밖에 고정되고(보드의 sticky와 같은 모습) 바탕색이 상태 바 뒤까지 덮는다. 목록은 바 아래 8부터.
 */
@Composable
internal fun DemoCategoryListScreen(route: DemoRoute.CategoryList, sourceKey: String?) {
    val c = LocalWLColors.current
    val nav = LocalWLNavigator.current
    val count = DemoContent.type(route.top, route.type)?.count ?: 0
    // 밀기 전환에서 아래 화면이 비치지 않게 바탕을 직접 칠한다.
    Box(Modifier.fillMaxSize().background(c.background)) {
        Column(Modifier.fillMaxSize()) {
            // 위에 붙는 머리 = 위쪽 바(바탕색, 상태 바 뒤까지 칠함). 목록은 그 아래에서만 스크롤해 상태 바 영역에 비치지 않는다.
            WLTopBar(
                background = c.background,
                // 화면의 뒤로 버튼도 시스템 뒤로와 같은 pop 전환을 쓴다(진행값 없는 뒤로).
                leading = { DemoCircleButton("뒤로", onClick = { nav.pop() }) { DemoIconView(DemoIcon.Back) } },
                title = {
                    // 제목 덩어리(제목 20/700 + 개수 18/700, 4, 상위 이름 13/500)를 바 안 세로 가운데에.
                    Column(verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s4)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8)) {
                            WLText(route.type, WLType.title.copy(fontSize = 20.sp), Modifier.weight(1f, fill = false).alignByBaseline(),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            WLText(count.toString(), WLType.price, Modifier.alignByBaseline(), color = c.textSecondary, maxLines = 1)
                        }
                        WLText(route.top, DemoCaption, color = c.textSecondary, maxLines = 1)
                    }
                },
            )
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                DemoItemGrid(
                    DemoContent.items,
                    sourceKeyPrefix = "list/${route.top}/${route.type}",
                    meta = { it.listMeta },
                    dot = { it.purpose?.color },
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = WishlistTokens.Space.s8),
                )
                TabBarSpacer()
            }
        }
    }
}

/** 상품 카드 2열 엇갈림(열 간격 12, 카드 세로 간격 20). 카드 → 상품 상세는 사진 이동. */
@Composable
internal fun DemoItemGrid(
    items: List<DemoItem>,
    sourceKeyPrefix: String,
    meta: (DemoItem) -> String,
    dot: (DemoItem) -> WLPurposeColor?,
    modifier: Modifier = Modifier,
) {
    Masonry2Col(modifier, gap = WishlistTokens.Space.s12, verticalGap = WishlistTokens.Space.s20) {
        items.forEach { DemoItemCard(it, sourceKey = "$sourceKeyPrefix/${it.id}", meta = meta(it), dot = dot(it)) }
    }
}

/**
 * 사진 상품 카드(FCategoryListL·FPurposeDetailL): 사진(모서리 20, 비율 그대로) + 8 + 이름 14 · 4 · 가격 18/700 · 4 · 메타 12/500.
 * 정보 보완이 필요한 상품은 사진 왼쪽 위에 32 표시(분류·목적 미확정)가 붙는다.
 */
@Composable
internal fun DemoItemCard(item: DemoItem, sourceKey: String, meta: String, dot: WLPurposeColor?) {
    val nav = LocalWLNavigator.current
    val c = LocalWLColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { nav.push(DemoRoute.Product(item.id), sourceKey) },
        verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f / item.ratio).wlSharedPhoto(sourceKey)) {
            DemoPhoto(item.look, Modifier.fillMaxSize())
            if (item.pending) {
                Box(
                    Modifier
                        .padding(WishlistTokens.Space.s8)
                        .size(32.dp)
                        .clip(RoundedCornerShape(WishlistTokens.Radius.xs))
                        .background(c.card)
                        .semantics { contentDescription = "분류·목적 미확정" },
                    contentAlignment = Alignment.Center,
                ) { DemoIconView(DemoIcon.Pending, 16.dp) }
            }
        }
        Column(Modifier.padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s4)) {
            WLText(keepAllBreaks(item.name), WLType.body)
            PriceText(item.price, item.currency)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (dot != null) PurposeDot(dot)
                WLText(meta, WLType.label, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

