import SwiftUI

/// 어두운 막. 항상 입력을 막는다(누른 것이 아래 화면에 닿지 않는다). 막이 사라지는 동안에도 마찬가지다.
/// 색은 토큰의 `scrimDim`(라이트 0.24·다크 0.45), 진행값 0...1을 opacity로 곱한다.
struct WLScrim: View {
    let progress: Double
    let color: Color
    let onTap: () -> Void

    var body: some View {
        Rectangle()
            .fill(color)
            .opacity(progress)
            .contentShape(Rectangle())
            .onTapGesture(perform: onTap)
            .ignoresSafeArea()
    }
}

/// 전환 중 모든 입력을 삼키는 투명 층(OverlayHost 맨 위).
struct InputBlocker: View {
    var body: some View {
        Color.clear
            .contentShape(Rectangle())
            .onTapGesture {}
            .gesture(DragGesture(minimumDistance: 0))
            .ignoresSafeArea()
            .accessibilityHidden(true)
    }
}
