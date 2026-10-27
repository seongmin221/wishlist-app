package app.wishlist.android.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.wishlist.android.R
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.WLButton
import app.wishlist.android.designsystem.WLButtonKind
import app.wishlist.android.designsystem.WLCard
import app.wishlist.android.designsystem.WLLineIcon
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.WLType
import app.wishlist.android.designsystem.WishlistTokens
import app.wishlist.android.feature.flow.HomeFlowRoute
import app.wishlist.android.navigation.LocalWLNavigator
import app.wishlist.android.ui.ItemDetailRoute
import app.wishlist.shared.presentation.HomeGroupCard
import app.wishlist.shared.presentation.HomeState

/**
 * FHome 로그인 뒤(C7). 할 일 섹션: 이 기기의 미전송 "분석 대기" 카드(로그인 전 카드와 같은 모양, 줄 → 로컬 대기 화면) 다음에
 * HOME-01 그룹 카드(보드 순서, 항목 있는 그룹만). 첫 HOME-01이 실패하면 그룹 자리에 다시 시도. 할 일이 없으면 섹션을 숨긴다.
 * 그 아래 "비교 중인 목적"(HOME-01 `recentPurposes`). 줄 key는 Compose `key`로만 쓴다.
 */
@Composable
internal fun HomeLoggedInContent(state: HomeState.LoggedIn, onRetry: () -> Unit) {
    val firstLoadFailed = !state.summaryLoaded && state.summaryError != null
    if (state.pending.isNotEmpty() || state.groups.isNotEmpty() || firstLoadFailed) {
        HomeSection(stringResource(R.string.home_todo)) {
            if (state.pending.isNotEmpty()) {
                var expanded by rememberExpanded("home/pending")
                HomeTodoCard(
                    icon = WLLineIcon.Clock,
                    title = stringResource(R.string.home_pending_title),
                    subtitle = stringResource(R.string.home_pending_subtitle_signed_in),
                    count = state.pending.size,
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                ) {
                    HomeTodoRows { state.pending.forEach { row -> key(row.key) { HomeLinkRow(row, WLLineIcon.Clock, tileSize = 44.dp, showsOriginal = true) } } }
                }
            }
            if (firstLoadFailed) SummaryRetryCard(onRetry)
            state.groups.forEach { group -> key(group.group) { HomeGroupTodoCard(group) } }
        }
    }
    if (state.purposes.isNotEmpty()) HomePurposeSummary(state.purposes)
}

/**
 * HOME-01 그룹 카드. 분류·목적 확인·정보 보완: 화살표 밖 → 첫 카드부터 연속 처리, 캐러셀 상품 → 그 상품의 anchor부터.
 * 분류 중: 카드 머리 전체가 펼치기, 펼친 줄 → 상품 상세.
 */
@Composable
private fun HomeGroupTodoCard(card: HomeGroupCard) {
    val nav = LocalWLNavigator.current
    val group = card.group
    var expanded by rememberExpanded("home/group/${group.name}")
    val opensFlow = HomeTodoText.opensFlow(group)
    HomeTodoCard(
        icon = HomeTodoText.icon(group),
        title = stringResource(HomeTodoText.title(group)),
        subtitle = stringResource(HomeTodoText.subtitle(group)),
        count = card.count,
        expanded = expanded,
        onExpandedChange = { expanded = it },
        onOpen = if (opensFlow) ({ nav.push(HomeFlowRoute(group, startAnchor = null), "home/flow/${group.name}") }) else null,
    ) {
        if (opensFlow) {
            HomeTodoCarousel(group, card.previews) { preview ->
                nav.push(HomeFlowRoute(group, preview.anchorCursor), "home/flow/${group.name}/${preview.item.id}")
            }
        } else {
            HomeTodoRows {
                card.previews.take(4).forEach { preview ->
                    key(preview.item.id) {
                        HomeProcessingRow(preview.item) { nav.push(ItemDetailRoute(preview.item.id), "home/processing/${preview.item.id}") }
                    }
                }
            }
        }
    }
}

/** 첫 HOME-01 실패: 할 일 카드 자리에 짧은 이유와 다시 시도(카드색 위라 먹색 주 버튼). */
@Composable
private fun SummaryRetryCard(onRetry: () -> Unit) {
    WLCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(WishlistTokens.Space.s20),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            WLText(stringResource(R.string.home_summary_failed), WLType.body, color = LocalWLColors.current.textSecondary)
            WLButton(stringResource(R.string.detail_retry), WLButtonKind.Primary, onClick = onRetry)
        }
    }
}
