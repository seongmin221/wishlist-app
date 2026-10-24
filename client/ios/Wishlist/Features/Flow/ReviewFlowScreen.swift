import Shared
import SwiftUI

/// FHomeReviewFlow (분류·목적 확인 연속 처리, C7). Header close · title · "n / 전체"; the card stack with the
/// swipe hint and the C7 notice ("확정·보류 저장은 다음 단계에서 연결돼요"); 보류 · 확정. Both are temporary passes,
/// so the end summary says only `넘김 n` (no 확정·보류 counts) with 홈으로 and 처음부터 다시 보기.
/// The pass is recorded when a throw starts (Ruling 10), so the last pass shows the summary while its card is
/// still leaving: the summary rises under the leaving card (Android draws it the same way).
struct ReviewFlowScreen: View {
    let owner: HomeFlowOwner

    @State private var leaving: ReviewCardStack.Leaving?

    var body: some View {
        let state = owner.state
        let done = state.phase == .done
        VStack(spacing: 0) {
            FlowHeader(title: String(localized: "home.group.review.title"), position: state.positionText)
            ZStack {
                if done {
                    FlowSummary(title: String(localized: "flow.review.done.title"),
                                summary: HomeTodoText.passedSummary(Int(state.passed)), restart: owner.restart)
                }
                if ReviewStackModel.showsStack(done: done, hasCurrent: state.current != nil, leaving: leaving != nil) {
                    ReviewCardStack(state: state, leaving: $leaving, onPass: owner.pass)
                } else if !done {
                    FlowStatus(state: state, retry: owner.retry)
                }
            }
        }
    }
}
