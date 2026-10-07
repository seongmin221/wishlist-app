package app.wishlist.android.feature.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import app.wishlist.android.designsystem.EmptyState
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.WLHeaderSheet
import app.wishlist.android.designsystem.WLIconTile
import app.wishlist.android.designsystem.WLOnSheet
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.WLTopBarMetrics
import app.wishlist.android.designsystem.wlTopBarBottom
import app.wishlist.android.designsystem.WLType
import app.wishlist.android.designsystem.WishlistTokens
import app.wishlist.android.designsystem.rememberWLHeaderSheetState
import kotlin.math.roundToInt

/** 펼친 상태의 아이콘 타일·이름(보드 접힌 헤더: 타일 36, 도현 20). */
private const val TileCollapsedScale = 36f / 44f
private const val NameCollapsedScale = 20f / 28f

/**
 * 목적 상세(FPurposeDetailL, 사용자 결정 2026-10-07: 후보 목록 = 바텀시트). 화면 바탕이 목적 색이고 상태 바 뒤까지 칠해진다. 탭 바 보임.
 * 뒤 층은 고정된 목적 색 머리, 앞 층은 후보 목록 시트(`WLHeaderSheet`)다. 시트를 올리면(진행값 p) 머리의 타일·이름이 맨 윗줄로
 * 줄며 올라가고, 설명·알약은 옅어지고, ⋯ 왼쪽에 "+"가 생긴다. 따로 겹치는 띠는 없다.
 */
@Composable
internal fun DemoPurposeDetailScreen(route: DemoRoute.Purpose, sourceKey: String?) {
    val p = DemoContent.purpose(route.purposeId) ?: return
    // 화면 바탕(상태 바 뒤 포함)이 목적 색이다. 밀기 전환에서도 이 바탕째 들어온다.
    DemoPurposeDetail(p)
}

@Composable
private fun DemoPurposeDetail(p: DemoPurpose) {
    val sheet = rememberWLHeaderSheetState()
    WLHeaderSheet(
        state = sheet,
        // 위쪽 바 아래 끝(안전 영역 + 62).
        expandedTop = wlTopBarBottom(),
        modifier = Modifier.background(p.color.face),
        header = { progress -> DemoPurposeHeader(p, progress) },
    ) {
        DemoCandidateList(p)
    }
}

/**
 * 머리 안 요소의 쉬는 위치(px). 배치 콜백 순서와 상관없게 모두 root 좌표로 받아 그리기 때 머리 기준으로 뺀다.
 * 진행값에 따른 이동 목표를 계산하는 데 쓴다.
 */
private class HeaderAnchors {
    var origin by mutableStateOf(Offset.Zero)
    var width by mutableFloatStateOf(0f)
    var topRowCenter by mutableFloatStateOf(0f)
    var tileRoot by mutableStateOf(Offset.Zero)
    var nameRoot by mutableStateOf(Offset.Zero)
    var nameHeight by mutableFloatStateOf(0f)

    /** 머리 기준 좌표. */
    val topRowCenterY get() = topRowCenter - origin.y
    val tile get() = tileRoot - origin
    val name get() = nameRoot - origin
}

/**
 * 목적 색 머리(고정): 맨 윗줄은 위쪽 바(`WLTopBar`, 다른 하위 화면과 같은 자리), 이름 줄은 버튼 아래 16부터, 좌우 20, 아래 28, 간격 16.
 * 쉬는 모습(p = 0)으로 배치하고 진행값은 그리기·배치 단계에서만 읽는다.
 * - 뒤로·⋯: 제자리. "+": ⋯ 왼쪽(간격 10)에 p 0.5→1 동안 나타남(scale 0.9→1), p < 0.5면 누르기·접근성 제외.
 * - 아이콘 타일 44 → 36: 뒤로 오른쪽(간격 10), 위쪽 바 세로 가운데로 올라감. 이름 도현 28 → 20: 타일 오른쪽(간격 8), "+" 왼쪽(간격 10)에서 끝남.
 * - 설명·알약: p 0→0.5 동안 옅어짐, 사라진 뒤 누르기·접근성 제외.
 */
@Composable
private fun DemoPurposeHeader(p: DemoPurpose, progress: () -> Float) {
    val c = LocalWLColors.current
    val on = WishlistTokens.Purpose.onPurpose
    val density = LocalDensity.current
    val anchors = remember { HeaderAnchors() }
    val faded by remember { derivedStateOf { progress() >= 0.5f } }
    val margin = with(density) { 20.dp.toPx() }
    val button = with(density) { WishlistTokens.Space.minTouch.toPx() }
    val gap10 = with(density) { 10.dp.toPx() }
    val tileTargetX = margin + button + gap10
    val nameTargetX = tileTargetX + with(density) { (36.dp + WishlistTokens.Space.s8).toPx() }
    val fadeOut = { (1f - progress() / 0.5f).coerceIn(0f, 1f) }

    val barHalf = with(density) { (WLTopBarMetrics.Height / 2).toPx() }
    Column(
        Modifier
            .fillMaxWidth()
            .onPlaced { anchors.origin = it.positionInRoot(); anchors.width = it.size.width.toFloat() },
    ) {
        // 위쪽 바 가운데 줄(바 아래 끝에서 28 위)이 펼친 모습의 타일·이름 세로 가운데다.
        DemoDetailTopButtons(
            Modifier.onPlaced { anchors.topRowCenter = it.positionInRoot().y + it.size.height - barHalf },
            middle = {
                DemoCircleButton(
                    "후보 추가",
                    onClick = {},
                    modifier = Modifier.graphicsLayer {
                        val a = ((progress() - 0.5f) / 0.5f).coerceIn(0f, 1f)
                        alpha = a
                        scaleX = lerp(0.9f, 1f, a)
                        scaleY = scaleX
                    },
                    enabled = faded,
                ) { DemoIconView(DemoIcon.Plus, 18.dp) }
            },
        )
        Column(
            // 이름 줄은 버튼 아래 16(= 바 아래 끝에서 10)부터.
            Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s16),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WLIconTile(
                        modifier = Modifier
                            .onPlaced { anchors.tileRoot = it.positionInRoot() }
                            .graphicsLayer {
                                val t = progress()
                                transformOrigin = TransformOrigin(0f, 0f)
                                scaleX = lerp(1f, TileCollapsedScale, t)
                                scaleY = scaleX
                                val targetY = anchors.topRowCenterY - size.height * TileCollapsedScale / 2f
                                translationX = (tileTargetX - anchors.tile.x) * t
                                translationY = (targetY - anchors.tile.y) * t
                            },
                        size = 44.dp,
                        radius = WishlistTokens.Radius.s,
                        color = c.card,
                    ) { DemoIconView(p.icon, 22.dp, c.text) }
                    Spacer(Modifier.width(WishlistTokens.Space.s12))
                    // 보드 h1: 높이 28 = 위 3 + 줄 상자 24(display28Edit) + 아래 1. 펼치면 20/28로 줄어 도현 20 한 줄(높이 20)이 된다.
                    WLText(
                        p.name,
                        WLType.display28Edit,
                        Modifier
                            .weight(1f)
                            .onPlaced {
                                anchors.nameRoot = it.positionInRoot()
                                anchors.nameHeight = it.size.height.toFloat()
                            }
                            // 글자 칸 폭: 쉬는 폭 → ("+" 왼쪽 10까지의 폭) ÷ 축소 비율. 말줄임이 줄어든 모습 기준으로 맞는다.
                            .layout { m, cs ->
                                val t = progress()
                                val targetVisual = anchors.width - margin - button - gap10 - button - gap10 - nameTargetX
                                val targetLayout = (targetVisual / NameCollapsedScale).coerceAtLeast(0f)
                                val w = lerp(cs.maxWidth.toFloat(), targetLayout, t).roundToInt().coerceAtLeast(0)
                                val placeable = m.measure(cs.copy(minWidth = w, maxWidth = w))
                                layout(cs.maxWidth, placeable.height) { placeable.place(0, 0) }
                            }
                            .graphicsLayer {
                                val t = progress()
                                transformOrigin = TransformOrigin(0f, 0f)
                                scaleX = lerp(1f, NameCollapsedScale, t)
                                scaleY = scaleX
                                val targetY = anchors.topRowCenterY - anchors.nameHeight * NameCollapsedScale / 2f
                                translationX = (nameTargetX - anchors.name.x) * t
                                translationY = (targetY - anchors.name.y) * t
                            }
                            .padding(top = 3.dp, bottom = 1.dp),
                        color = on,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (p.description != null) {
                    // 보드 p: 위 간격 2 + 8, 높이 21.75 = 위 3 + 줄 15 + 아래 2.75 + 1.
                    WLText(
                        p.description,
                        WLType.body.copy(fontSize = 15.sp, lineHeight = 15.sp),
                        Modifier
                            .graphicsLayer { alpha = fadeOut() }
                            .then(if (faded) Modifier.clearAndSetSemantics {} else Modifier)
                            .padding(top = 10.dp + 3.dp, bottom = 3.75.dp),
                        color = on,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                Modifier.graphicsLayer { alpha = fadeOut() }.then(if (faded) Modifier.clearAndSetSemantics {} else Modifier),
                horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8),
            ) {
                DemoPillButton("후보 추가", DemoIcon.Plus, c.card, c.text, enabled = !faded)
                if (p.candidates > 0) DemoPillButton("비교 끝내기", DemoIcon.Archive, c.text, c.onInverse, enabled = !faded)
            }
        }
    }
}

/** 머리 알약 버튼(높이 44, 안쪽 0/18/0/14, 아이콘 18 + 6 + 15/500). */
@Composable
private fun DemoPillButton(text: String, icon: DemoIcon, bg: Color, fg: Color, enabled: Boolean) {
    Row(
        Modifier
            .height(WishlistTokens.Space.minTouch)
            .clip(RoundedCornerShape(WishlistTokens.Radius.pill))
            .background(bg)
            .clickable(enabled = enabled, role = Role.Button) {}
            .padding(start = 14.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DemoIconView(icon, 18.dp, fg)
        WLText(text, WLType.body.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium), color = fg, maxLines = 1)
    }
}

/**
 * 시트 내용(손잡이 줄 아래): 위 12, 좌우 16, 아래 탭 바 여백, 간격 12. "후보 5" + 2열 엇갈림 카드.
 * 후보가 없으면 400 높이 가운데 빈 상태(분기 없이 같은 시트 동작).
 */
@Composable
private fun DemoCandidateList(p: DemoPurpose) {
    Column(
        Modifier.padding(start = WishlistTokens.Space.s16, end = WishlistTokens.Space.s16, top = WishlistTokens.Space.s12, bottom = tabBarClearance()),
        verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s12),
    ) {
        if (p.candidates > 0) {
            Row(Modifier.padding(horizontal = WishlistTokens.Space.s4), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WLText("후보", WLType.body.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold), Modifier.alignByBaseline(), maxLines = 1)
                WLText(p.candidates.toString(), WLType.price, Modifier.alignByBaseline(), maxLines = 1)
            }
            DemoItemGrid(
                DemoContent.candidates.take(p.candidates),
                sourceKeyPrefix = "purpose/${p.id}",
                meta = { it.candidateMeta },
                dot = { null },
            )
        } else {
            WLOnSheet {
                Box(Modifier.fillMaxWidth().height(400.dp), contentAlignment = Alignment.Center) {
                    EmptyState("아직 후보가 없어요", keepAllBreaks("저장해 둔 상품 중에서 이 목적으로 비교할 후보를 골라 주세요.")) {
                        DemoIconView(DemoIcon.PurposeTab, 24.dp)
                    }
                }
            }
        }
    }
}
