import Shared
import SwiftUI

/// FHome(C3-D3): "분류 중" 카드 하나. 이 계정의 미전송(보내는 중·연결되면 보내요·보낼 수 없는 링크)과 서버에서 분석 중인
/// 항목을 한 목록으로 보여 주고, 줄이 없으면 할 일 섹션을 숨긴다. 줄에는 오른쪽 동작이 없다(Ruling 15: 보드의 삭제는
/// C4/C8). 줄 key는 전송이 끝나면 `local-…` → `item-…`로 바뀌는 불투명 값이라 목록 정체성으로만 쓴다.
struct HomeLoggedInView: View {
    let processing: [HomeRow]

    @State private var expanded = false

    var body: some View {
        if !processing.isEmpty {
            HomeSection(label: String(localized: "home.todo")) {
                HomeTodoCard(
                    icon: .sorting,
                    title: String(localized: "home.processing.title"),
                    subtitle: String(localized: "home.processing.subtitle"),
                    count: processing.count,
                    expanded: $expanded
                ) {
                    ForEach(processing, id: \.key) { row in
                        HomeLinkRow(row: row, icon: .sorting, tileSize: 52)
                    }
                }
            }
        }
    }
}
