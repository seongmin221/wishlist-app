import SwiftUI
import Shared

private let priceFormatter = PriceFormatter()

/// Pass ProductSnapshot.price?.canonical directly; shared domain owns parsing and display.
func formatPrice(_ amountText: String?, currency: String?) -> String? {
    priceFormatter.formatOrNull(amount: amountText, currency: currency)
}

/// 가격(Plex 700 tabular). 기본 `WLTextStyle.price`.
/// 한 줄에 들어가면 한 줄, 큰 글자에서는 통화 코드와 금액 사이에서만 줄을 바꾼다.
/// 표시 가능한 공통 결과가 없으면 가격 뷰와 접근성 요소를 만들지 않는다.
struct PriceText: View {
    let amountText: String?
    let currency: String?
    var style: WLTextStyle = .price
    var color: Color?

    var body: some View {
        if let text = formatPrice(amountText, currency: currency) {
            let parts = text.split(separator: " ", maxSplits: 1).map(String.init)
            ViewThatFits(in: .horizontal) {
                WLText(text, style, color: color)
                    .lineLimit(1)
                    .fixedSize(horizontal: true, vertical: false)
                VStack(alignment: .leading, spacing: 0) {
                    WLText(parts.first ?? text, style, color: color).lineLimit(1)
                    WLText(parts.count > 1 ? parts[1] : "", style, color: color)
                        .lineLimit(1)
                        .minimumScaleFactor(0.5)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(text)
            .accessibilityAddTraits(.isStaticText)
        }
    }
}
