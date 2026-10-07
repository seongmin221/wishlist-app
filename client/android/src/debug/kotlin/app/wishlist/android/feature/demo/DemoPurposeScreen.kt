package app.wishlist.android.feature.demo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.WLIconTile
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.WLType
import app.wishlist.android.designsystem.WishlistTokens
import app.wishlist.android.designsystem.WishlistTokens.Curve
import app.wishlist.android.navigation.LocalWLNavigator
import app.wishlist.android.navigation.WLScrollToTopEffect
import app.wishlist.android.navigation.WLTab

/** 겹쳐 쌓인 카드에서 다음 카드가 앞 카드 아래를 덮는 높이(보드 margin-top −22). */
private val CardOverlap = 22.dp

/** 목적 카드 펼침·접힘 높이 전환: 300 `emphasized`(motion.md 구현 기본값 "목적 카드 펼침"). */
private const val PurposeExpandMillis = 300

/**
 * 목적 탭 첫 화면(FPurposeHomeL): 목적 색 카드가 겹쳐 쌓이고 하나만 펼쳐져 후보 썸네일을 보여 준다.
 * 접힌 카드를 누르면 그 카드가 펼쳐지고(다른 카드는 접힘), 펼친 카드를 한 번 더 누르면 목적 상세로 간다(가로 밀기).
 * 아래에 점선 "목적 추가"와 "끝난 비교" 카드가 있다(데모에서는 동작 없음).
 */
@Composable
fun DemoPurposeScreen() {
    val c = LocalWLColors.current
    val nav = LocalWLNavigator.current
    val scroll = rememberScrollState()
    WLScrollToTopEffect(WLTab.Purpose, scroll)
    var open by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().background(c.background).verticalScroll(scroll)) {
        Box(Modifier.padding(start = WishlistTokens.Space.screenMargin, end = WishlistTokens.Space.screenMargin, bottom = 20.dp)) {
            DemoTabHeader("목적", "비교 중 ${DemoContent.purposes.size}개 · 최근 활동순")
        }
        OverlapColumn(CardOverlap, Modifier.padding(horizontal = WishlistTokens.Space.s16)) {
            DemoContent.purposes.forEachIndexed { i, p ->
                val key = "purpose/card/${p.id}"
                DemoPurposeCard(p, expanded = i == open) {
                    if (i == open) nav.push(DemoRoute.Purpose(p.id), key) else open = i
                }
            }
        }
        DashedAddButton("목적 추가", Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp))
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, top = WishlistTokens.Space.s32),
            verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s12),
        ) {
            WLText("끝난 비교", DemoCaption, color = c.textSecondary)
            DemoArchiveCard()
        }
        TabBarSpacer()
    }
}

/** 자식을 위에서부터 쌓되 둘째부터 `overlap`만큼 앞 자식 아래를 덮는다. 뒤 자식이 위에 그려진다. */
@Composable
private fun OverlapColumn(overlap: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier.fillMaxWidth()) { measurables, constraints ->
        val o = overlap.roundToPx()
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        val height = placeables.sumOf { it.height } - o * (placeables.size - 1).coerceAtLeast(0)
        layout(constraints.maxWidth, height.coerceAtLeast(0)) {
            var y = 0
            placeables.forEach { p ->
                p.place(0, y)
                y += p.height - o
            }
        }
    }
}

/**
 * 목적 카드: 모서리 28, 2 바탕색 테두리(겹친 경계선), 목적 색 면, 먹색 글자.
 * 머리 줄(최소 64, 안쪽 14/20/30/14): 아이콘 타일 36 + 12 + 이름 도현 28 ↔ 개수 26/700.
 * 펼침 본문(0/20/40, 머리 아래 −16, 간격 14): 메타 12/500 + 후보 썸네일 4칸(후보가 없으면 없음).
 */
@Composable
private fun DemoPurposeCard(p: DemoPurpose, expanded: Boolean, onClick: () -> Unit) {
    val c = LocalWLColors.current
    val on = WishlistTokens.Purpose.onPurpose
    val shape = RoundedCornerShape(WishlistTokens.Radius.l)
    val spec = tween<IntSize>(PurposeExpandMillis, easing = Curve.emphasized)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.color.face)
            .border(2.dp, c.background, shape)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 20.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                WLIconTile(size = 36.dp, radius = 12.dp, color = c.card) { DemoIconView(p.icon, 18.dp, c.text) }
                WLText(p.name, WLType.display28, color = on, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            WLText(p.candidates.toString(), WLType.price.copy(fontSize = 26.sp), color = on, maxLines = 1)
        }
        // 접힘: 머리 아래 여백 30 = 14 + 16. 펼침: 14 + 본문(보드 margin-top −16을 이 16을 빼서 맞춘다).
        AnimatedVisibility(!expanded, enter = expandVertically(spec), exit = shrinkVertically(spec)) {
            Spacer(Modifier.height(16.dp))
        }
        AnimatedVisibility(expanded, enter = expandVertically(spec), exit = shrinkVertically(spec)) {
            Column(
                Modifier.padding(start = 20.dp, end = 20.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                WLText(p.meta, WLType.label, color = on, maxLines = 1)
                if (p.candidates > 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8)) {
                        DemoContent.candidates.take(4).forEach { item ->
                            // 썸네일: 정사각, 모서리 14, 안쪽 10 + 사진(그림이 안쪽을 가득 채운다).
                            Box(
                                Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(WishlistTokens.Radius.s))
                                    .background(item.look.face)
                                    .padding(10.dp),
                            ) { DemoPhoto(item.look, Modifier.fillMaxSize(), fraction = 1f) }
                        }
                    }
                }
            }
        }
    }
}

/** 점선 큰 버튼(전체 폭, 최소 56, 모서리 28, 1.5 점선 보조색, + 18 + 16/700). */
@Composable
private fun DashedAddButton(text: String, modifier: Modifier = Modifier) {
    val c = LocalWLColors.current
    val dash = c.textSecondary
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(WishlistTokens.Radius.l))
            .drawBehind {
                val w = 1.5.dp.toPx()
                drawRoundRect(
                    color = dash,
                    topLeft = Offset(w / 2, w / 2),
                    size = Size(size.width - w, size.height - w),
                    cornerRadius = CornerRadius(WishlistTokens.Radius.l.toPx() - w / 2),
                    style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            }
            .clickable(role = Role.Button) {},
        horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DemoIconView(DemoIcon.Plus, 18.dp)
        WLText(text, WLType.button, maxLines = 1)
    }
}

/** 끝난 비교 카드(최소 64, 좌우 16, 모서리 28, 카드색): 아이콘 타일 44 + "아카이브 2" + 보관 목적 이름 + chevron. */
@Composable
private fun DemoArchiveCard() {
    val c = LocalWLColors.current
    val strong = WLType.button
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(WishlistTokens.Radius.l))
            .background(c.card)
            .clickable(role = Role.Button) {}
            .padding(horizontal = WishlistTokens.Space.s16, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s12),
    ) {
        WLIconTile { DemoIconView(DemoIcon.Archive, 18.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WLText("아카이브", strong, Modifier.alignByBaseline(), maxLines = 1)
                WLText("2", WLType.price.copy(fontSize = 16.sp), Modifier.alignByBaseline(), maxLines = 1)
            }
            WLText("겨울 패딩 · 기계식 키보드", WLType.label, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DemoIconView(DemoIcon.ChevronRight, 18.dp)
    }
}
