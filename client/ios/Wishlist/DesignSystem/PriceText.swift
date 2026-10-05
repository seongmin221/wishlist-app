import SwiftUI

/// "KRW 549,000", "USD 299", "USD 19.99". 로케일과 무관하게 ','로 묶는다. 소수는 0이 아닐 때만 두 자리, KRW는 소수 없음.
/// (C2에서 KMP domain으로 옮긴다. 지금은 컴포넌트 미리보기용.)
func formatPrice(_ amount: Decimal, currency: String) -> String {
    let scale = currency == "KRW" ? 0 : 2
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
        let cents = NSDecimalNumber(decimal: fraction * 100).intValue
        tail = "." + (cents < 10 ? "0\(cents)" : "\(cents)")
    }
    return "\(currency) \(sign)\(grouped)\(tail)"
}

/// 가격(Plex 700 tabular). 기본 `WLTextStyle.price`.
struct PriceText: View {
    let amount: Decimal
    let currency: String
    var style: WLTextStyle = .price
    var color: Color?

    var body: some View {
        WLText(formatPrice(amount, currency: currency), style, color: color)
    }
}
