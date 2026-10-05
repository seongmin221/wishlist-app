import SwiftUI

/// 카드 면(모서리 l 28, 카드색). 시트·확인창 위에서는 묶음 면(`sheetField`)을 쓴다. `onClick`이 있으면 누를 수 있다.
struct WLCard<Content: View>: View {
    var radius: CGFloat = WishlistTokens.Radius.l
    var onClick: (() -> Void)?
    @ViewBuilder var content: () -> Content

    @Environment(\.wlColors) private var c
    @Environment(\.wlOnSheet) private var onSheet

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        let face = content().background(onSheet ? c.sheetField : c.card, in: shape).clipShape(shape)
        if let onClick {
            Button(action: onClick) { face.contentShape(shape) }.buttonStyle(.plain)
        } else {
            face
        }
    }
}
