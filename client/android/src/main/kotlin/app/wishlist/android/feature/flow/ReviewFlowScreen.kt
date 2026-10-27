package app.wishlist.android.feature.flow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import app.wishlist.android.R
import app.wishlist.android.designsystem.WLLineIcon
import app.wishlist.shared.presentation.HomeFlowPhase
import app.wishlist.shared.presentation.HomeFlowPresenter
import app.wishlist.shared.presentation.HomeFlowState

/** 보드 카드 묶음 높이(390×844 기준). 더 낮은 화면에서는 남은 높이만큼 줄고 사진이 작아진다. */
private val StackMaxHeight = 574.dp

/**
 * 분류·목적 확인(FHomeReviewFlow, C7 사용자 답변 1): 확정·보류는 버튼·밀기 모두 카드 던지기 모션을 그대로 하고
 * `pass()`(이번 세션의 임시 넘김)만 부른다. 서버에는 아무것도 저장하지 않는다. 끝 요약은 `넘김 n`만 보인다.
 * 버튼 위 안내에 보드 문구와 "확정·보류 저장은 다음 단계에서 연결돼요"를 함께 둔다.
 */
@Composable
internal fun ReviewFlowScreen(title: String, state: HomeFlowState, presenter: HomeFlowPresenter) {
    val motion = rememberReviewStackMotion(presenter::pass)
    val current = state.current
    ResetDragOnSwap(current, motion)
    val done = state.phase == HomeFlowPhase.DONE
    FlowHeader(title, position = if (!done && current != null) HomeFlowText.position(state.position, state.total) else null)
    Box(Modifier.fillMaxSize()) {
        if (done) {
            FlowSummary(stringResource(R.string.flow_review_done), HomeFlowText.passedSummary(state.passed), presenter::restart, appear = true)
        }
        Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = flowBottomPadding())) {
            // Bottom 20: the back cards reach 6 below the stack and turn up to 3°; they stay clear of the hint lines.
            Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = 20.dp)) {
                // After the last card the summary takes the place; a card still flying out is drawn over it.
                if (!done || motion.leaving != null) {
                    ReviewCardStack(
                        current = current.takeIf { !done },
                        upcoming = if (done) 0 else state.upcoming.size,
                        motion = motion,
                        modifier = Modifier.fillMaxWidth().heightIn(max = StackMaxHeight).fillMaxHeight().align(Alignment.TopCenter),
                    )
                }
            }
            if (!done) {
                FlowHints(stringResource(R.string.flow_review_hint), stringResource(R.string.flow_review_not_saved))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FlowButton(
                        stringResource(R.string.flow_review_hold), primary = false,
                        onClick = { current?.let { motion.throwCard(it, ThrowDecision.Left) } },
                        modifier = Modifier.weight(1f), leadingIcon = WLLineIcon.ArrowLeft, enabled = current != null,
                    )
                    FlowButton(
                        stringResource(R.string.flow_review_confirm), primary = true,
                        onClick = { current?.let { motion.throwCard(it, ThrowDecision.Right) } },
                        modifier = Modifier.weight(1f), trailingIcon = WLLineIcon.ArrowRight, enabled = current != null,
                    )
                }
            }
        }
    }
}

/** 보드 하단 여백 36, 내비게이션 막대가 높으면 그 위 12(상세 하단 버튼과 같은 규칙). */
@Composable
internal fun flowBottomPadding(): Dp = max(36.dp, WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp)
