package app.wishlist.android.feature.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.PriceText
import app.wishlist.android.designsystem.PurposeDot
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.WLTopBar
import app.wishlist.android.designsystem.WLTopBarMetrics
import app.wishlist.android.designsystem.wlSafeTop
import app.wishlist.android.designsystem.WLType
import app.wishlist.android.designsystem.WishlistTokens
import app.wishlist.android.designsystem.overlay.LocalOverlayHostState
import app.wishlist.android.designsystem.overlay.rememberWLMenuAnchor
import app.wishlist.android.designsystem.overlay.wlAnchor
import app.wishlist.android.navigation.LocalWLNavigator
import app.wishlist.android.navigation.wlSharedPhoto

/** 상품 상세 하단 바 아래 여백: 보드 36(홈 표시줄 포함). 내비게이션 막대가 더 높으면 그 위 12(구현 기본값). */
@Composable
private fun bottomBarBottomPadding(): Dp =
    max(36.dp, WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + WishlistTokens.Space.s12)

/** 하단 바 높이(위 12 + 버튼 56 + 아래 여백). 스크롤 내용 끝을 이만큼 + 20 비운다(보드 padding-bottom 140). */
@Composable
private fun bottomBarHeight(): Dp = WishlistTokens.Space.s12 + 56.dp + bottomBarBottomPadding()

/**
 * 상품 상세(FProductDetailL). 탭 바 없음.
 * - 뒤로·⋯는 스크롤과 무관하게 위쪽 바(`WLTopBar`, 배경 없음) 자리에 떠 있다.
 * - 사진: 버튼 아래 12부터 좌우 20, 정사각 칸(모서리 20, 안쪽 56, 사진 바탕색) 안에 사진을 원래 비율로 맞춰(aspect-fit) 그린다.
 *   사진 이동의 목표 사각형은 이 fit된 사진이다. 카드 사진과 비율이 같아 마지막 프레임에서 크기·모양이 바뀌지 않는다.
 */
@Composable
internal fun DemoProductDetailScreen(route: DemoRoute.Product, sourceKey: String?) {
    val item = DemoContent.item(route.itemId) ?: return
    val c = LocalWLColors.current
    Box(Modifier.fillMaxSize().background(c.background)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // 사진 칸은 위쪽 바 버튼 아래 12부터(안전 영역 + 68).
            Spacer(Modifier.height(wlSafeTop() + WLTopBarMetrics.ButtonTop + WishlistTokens.Space.minTouch + WishlistTokens.Space.s12))
            Box(
                Modifier
                    .padding(horizontal = WishlistTokens.Space.screenMargin)
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(WishlistTokens.Radius.m))
                    // 보드는 라이트·다크 모두 #FFFFFF(테마 토큰이 아니다): 흰 바탕 상품 사진의 바탕이 칸 전체로 이어진 모습이다.
                    // 그래서 카드색 대신 사진 바탕색으로 칸을 채운다(다크에서 카드색이면 흰 사진이 어두운 칸 안에 떠 보인다).
                    .background(item.look.face)
                    .padding(56.dp),
                contentAlignment = Alignment.Center,
            ) {
                // aspect-fit: 세로가 긴 사진은 높이를, 가로가 긴(정사각 포함) 사진은 폭을 먼저 채운다.
                val photo = Modifier.aspectRatio(1f / item.ratio, matchHeightConstraintsFirst = item.ratio > 1f)
                DemoPhoto(item.look, if (sourceKey != null) photo.wlSharedPhoto(sourceKey) else photo)
            }
            Column(
                Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp),
                verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s20),
            ) {
                Column {
                    WLText(item.brand, WLType.body.copy(fontWeight = FontWeight.Bold), maxLines = 1)
                    Spacer(Modifier.height(WishlistTokens.Space.s8))
                    WLText(item.model, WLType.title)
                    Spacer(Modifier.height(10.dp))
                    PriceText(item.price, item.currency, style = WLType.price.copy(fontSize = 24.sp))
                    Spacer(Modifier.height(WishlistTokens.Space.s4))
                    WLText(
                        keepAllBreaks("${item.checked} 확인한 가격이에요. 지금 가격은 원본에서 확인해 주세요."),
                        WLType.body.copy(fontSize = 13.sp, lineHeight = 1.5.em),
                        color = c.textSecondary,
                    )
                }
                DemoInfoCard(item)
                WLText("9월 28일 저장", DemoCaption, color = c.textSecondary, maxLines = 1)
            }
            Spacer(Modifier.height(bottomBarHeight() + WishlistTokens.Space.s20))
        }
        // 뒤로·⋯: 배경 없는 위쪽 바(스크롤 위에 떠 있음).
        DemoDetailTopButtons()
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(c.background)
                .padding(start = 20.dp, end = 20.dp, top = WishlistTokens.Space.s12, bottom = bottomBarBottomPadding()),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(WishlistTokens.Radius.pill))
                    .background(c.text)
                    .clickable(role = Role.Button) {},
                horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WLText("원본 보기", WLType.button, color = c.onInverse, maxLines = 1)
                DemoIconView(DemoIcon.External, 18.dp, c.onInverse)
            }
        }
    }
}

/**
 * 위쪽 바의 뒤로·⋯(44 카드색 원, 배경 없는 `WLTopBar`). ⋯는 데모 메뉴(편집·삭제)를 연다. `middle`은 ⋯ 왼쪽(간격 10)에 놓인다.
 * 모든 하위 화면이 이 바를 써서 뒤로 버튼 자리가 같다(안전 영역 + 12, 왼쪽 20).
 */
@Composable
internal fun DemoDetailTopButtons(modifier: Modifier = Modifier, middle: (@Composable () -> Unit)? = null) {
    val nav = LocalWLNavigator.current
    val overlay = LocalOverlayHostState.current
    val anchor = rememberWLMenuAnchor()
    WLTopBar(
        modifier,
        // 화면의 뒤로 버튼도 시스템 뒤로와 같은 pop 전환을 쓴다(진행값 없는 뒤로).
        leading = { DemoCircleButton("뒤로", onClick = { nav.pop() }) { DemoIconView(DemoIcon.Back) } },
        trailing = {
            middle?.invoke()
            DemoCircleButton(
                "더 보기",
                onClick = { anchor.boundsInWindow()?.let { overlay.showMenu(it, demoMenuItems(overlay)) } },
                modifier = Modifier.wlAnchor(anchor),
            ) { DemoIconView(DemoIcon.More) }
        },
    )
}

/** 정보 카드(모서리 28, 위아래 4): 줄 최소 56·좌우 16, 라벨 폭 60 14 보조색, 값 15/700 오른쪽 정렬. */
@Composable
private fun DemoInfoCard(item: DemoItem) {
    val c = LocalWLColors.current
    val value = WLType.body.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WishlistTokens.Radius.l))
            .background(c.card)
            .padding(vertical = WishlistTokens.Space.s4),
    ) {
        DemoInfoRow("카테고리") { WLText(item.category, value, textAlign = TextAlign.End) }
        DemoInfoRow("목적") {
            val purpose = item.purpose
            if (purpose != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8)) {
                    PurposeDot(purpose.color)
                    WLText(purpose.name, value)
                }
            } else {
                WLText("목적 미지정", value.copy(fontWeight = FontWeight.Normal), color = c.textSecondary)
            }
        }
    }
}

@Composable
private fun DemoInfoRow(label: String, value: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = WishlistTokens.Space.s16),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s12),
    ) {
        WLText(label, WLType.body, Modifier.width(60.dp), color = LocalWLColors.current.textSecondary, maxLines = 1)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { value() }
    }
}
