import Shared
import SwiftUI

/// FHomeReviewFlow (분류·목적 확인 연속 처리, C7). Header close · title · "n / 전체"; the card stack with the
/// swipe hint and the C7 notice ("확정·보류 저장은 다음 단계에서 연결돼요"); 보류 · 확정. Both are temporary passes,
/// so the end summary says only `넘김 n` (no 확정·보류 counts) with 홈으로 and 처음부터 다시 보기.
struct ReviewFlowScreen: View {
    let owner: HomeFlowOwner

    var body: some View {
        let state = owner.state
        VStack(spacing: 0) {
            FlowHeader(title: String(localized: "home.group.review.title"), position: state.positionText)
            if state.phase == .done {
                FlowSummary(title: String(localized: "flow.review.done.title"),
                            summary: HomeTodoText.passedSummary(Int(state.passed)), restart: owner.restart)
            } else if state.current != nil {
                ReviewCardStack(state: state, onPass: owner.pass)
            } else {
                FlowStatus(state: state, retry: owner.retry)
            }
        }
    }
}
