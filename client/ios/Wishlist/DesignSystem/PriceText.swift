import SwiftUI

/// ISO 통화 메타데이터를 한 번만 읽는다. 알 수 없는 코드는 호출할 때마다 formatter를 만들지 않고 2자리를 쓴다.
private enum WLCurrencyFractionDigits {
    static let values: [String: Int] = {
        let formatter = NumberFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.numberStyle = .currency
        var values: [String: Int] = [:]
        for currency in Locale.Currency.isoCurrencies.map(\.identifier) {
            formatter.currencyCode = currency
            values[currency] = formatter.maximumFractionDigits
        }
        return values
    }()
}

/// "KRW 549,000", "USD 299", "USD 19.99". 로케일과 무관하게 ','로 묶는다. 소수는 0이 아닐 때만 ISO 4217 통화별 자리 수로 표시한다.
/// (C2에서 KMP domain으로 옮긴다. 지금은 컴포넌트 미리보기용.)
func formatPrice(_ amount: Decimal, currency: String) -> String {
    let currency = currency.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    let scale = WLCurrencyFractionDigits.values[currency] ?? 2
    var source = amount
    var v = Decimal()
    NSDecimalRound(&v, &source, scale, .plain) // 절반은 0에서 먼 쪽으로(Java HALF_UP)
    var magnitude = v < 0 ? -v : v
    var whole = Decimal()
    NSDecimalRound(&whole, &magnitude, 0, .down)
    let fraction = magnitude - whole
    let digits = "\(whole)"
    var grouped = ""
    for (i, ch) in digits.reversed().enumerated() {
        if i > 0 && i % 3 == 0 { grouped.append(",") }
        grouped.append(ch)
    }
    grouped = String(grouped.reversed())
    let sign = v < 0 ? "-" : ""
    var tail = ""
    if scale > 0 && fraction != 0 {
        let units = NSDecimalNumber(decimal: fraction * pow(Decimal(10), scale)).stringValue
        tail = "." + String(repeating: "0", count: max(0, scale - units.count)) + units
    }
    return "\(currency) \(sign)\(grouped)\(tail)"
}

/// 가격(Plex 700 tabular). 기본 `WLTextStyle.price`.
/// 한 줄에 들어가면 한 줄(디자인 결정 2026-10-04: 가장 좁은 카드에서도 KRW 1,190,000은 한 줄). 큰 글자 크기에서 넘치면
/// 통화 코드와 금액 사이에서만 줄을 바꾸고, 금액은 숫자 중간에서 끊지 않는다(그래도 넘치면 금액만 줄여 한 줄에 둔다).
/// 숫자 중간 줄바꿈("1,190,00 / 0")은 Task 7 접근성 최대 크기 확인에서 찾았다.
struct PriceText: View {
    let amount: Decimal
    let currency: String
    var style: WLTextStyle = .price
    var color: Color?

    var body: some View {
        let text = formatPrice(amount, currency: currency)
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
