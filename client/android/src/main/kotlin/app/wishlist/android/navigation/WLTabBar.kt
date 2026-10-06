package app.wishlist.android.navigation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import app.wishlist.android.R
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.WLType
import app.wishlist.android.designsystem.WishlistTokens
import kotlinx.coroutines.delay

internal val WLTab.label: String
    @Composable get() = stringResource(when (this) {
        WLTab.Home -> R.string.wl_tab_home
        WLTab.Category -> R.string.wl_tab_category
        WLTab.Purpose -> R.string.wl_tab_purpose
    })

/** 탭 바 높이(64)와 아래 여백(24). 탭 첫 화면이 내용 끝에 이만큼 비워 둔다. */
internal val WLTabBarHeight = 64.dp
internal val WLTabBarBottomGap = 24.dp

/** 탭 글자는 글자 크기 배율을 1.3까지만 따른다(알약 칸 폭 114에 "카테고리"가 들어가야 한다). */
private const val TabLabelMaxFontScale = 1.3f

/** 탭 바 아래 여백: 24, 시스템 내비게이션 막대가 더 높으면 그 위 8. */
@Composable
internal fun wlTabBarBottomPadding() =
    max(WLTabBarBottomGap, WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 8.dp)

/**
 * 먹색 알약 바 + 흰 선택 알약(디자인 결정 2026-10-02·2026-10-05). 알약은 250 `standard`로 미끄러지고,
 * 글자색(흰색 ↔ 먹색)·굵기(400 ↔ 700)는 125에 바꾼다.
 */
@Composable
fun WLTabBar(current: WLTab, onSelect: (WLTab) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalWLColors.current
    val pos by animateFloatAsState(
        targetValue = current.ordinal.toFloat(),
        animationSpec = tween(WishlistTokens.Motion.tabPill, easing = WishlistTokens.Curve.standard),
        label = "tabPill",
    )
    var labelTab by remember { mutableStateOf(current) }
    LaunchedEffect(current) {
        if (labelTab != current) {
            delay(WishlistTokens.Motion.tabLabelSwapAt.toLong())
            labelTab = current
        }
    }
    val density = LocalDensity.current
    val capped = remember(density) { Density(density.density, density.fontScale.coerceAtMost(TabLabelMaxFontScale)) }
    Row(
        modifier
            .padding(horizontal = WishlistTokens.Space.s16)
            .padding(bottom = wlTabBarBottomPadding())
            .fillMaxWidth()
            .heightIn(min = WLTabBarHeight)
            .background(c.tabBar, RoundedCornerShape(WishlistTokens.Radius.pill))
            .padding(WishlistTokens.Space.s8)
            .drawBehind {
                val w = size.width / WLTab.entries.size
                drawRoundRect(
                    color = c.tabPill,
                    topLeft = Offset(pos * w, 0f),
                    size = Size(w, size.height),
                    cornerRadius = CornerRadius(size.height / 2f),
                )
            }
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalDensity provides capped) {
            WLTab.entries.forEach { tab ->
                val selected = tab == labelTab
                val color = if (selected) c.onTabPill else c.onTabBar
                Row(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .selectable(
                            selected = tab == current,
                            onClick = { onSelect(tab) },
                            role = Role.Tab,
                            interactionSource = null,
                            indication = null,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TabIcon(tab, color)
                    WLText(
                        tab.label,
                        WLType.body.copy(fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal),
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private val HomePath = PathParser().parsePathString("M4 11l8-7 8 7v9h-5v-6H9v6H4z").toPath()

/** 보드의 24 격자 선 아이콘(선 1.8)을 20 크기로 그린다. */
@Composable
private fun TabIcon(tab: WLTab, color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val k = size.width / 24f
        scale(k, k, pivot = Offset.Zero) {
            val stroke = Stroke(width = 1.8f)
            when (tab) {
                WLTab.Home -> drawPath(HomePath, color, style = stroke)
                WLTab.Category -> listOf(4f to 4f, 13f to 4f, 4f to 13f, 13f to 13f).forEach { (x, y) ->
                    drawRoundRect(color, Offset(x, y), Size(7f, 7f), CornerRadius(2f), style = stroke)
                }
                WLTab.Purpose -> {
                    drawRoundRect(color, Offset(4f, 8f), Size(16f, 12f), CornerRadius(3f), style = stroke)
                    drawLine(color, Offset(7f, 5f), Offset(17f, 5f), strokeWidth = 1.8f)
                }
            }
        }
    }
}
