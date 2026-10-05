import SwiftUI

/// 입력칸: 테두리 없는 면(카드색, 시트·확인창 위는 sheetField)·모서리 m 20·높이 56(여러 줄이면 `minLines`로 늘어난다).
/// 위 라벨(13)과 오른쪽 보조 글자(예: "최대 40자")는 `label`·`hint`로 준다.
struct WLInput: View {
    @Binding var value: String
    var label: String?
    var hint: String?
    var placeholder = ""
    var singleLine = true
    var minLines = 1
    var maxLength = Int.max
    var keyboard: UIKeyboardType = .default

    @Environment(\.wlColors) private var c
    @Environment(\.wlOnSheet) private var onSheet

    private var limited: Binding<String> {
        Binding(get: { value }, set: { if $0.count <= maxLength { value = $0 } })
    }

    var body: some View {
        let face = onSheet ? c.sheetField : c.card
        VStack(alignment: .leading, spacing: WishlistTokens.Space.s8) {
            if label != nil || hint != nil {
                HStack {
                    WLText(label ?? "", WLTextStyle.body.resized(13), color: c.textSecondary)
                    Spacer(minLength: 0)
                    WLText(hint ?? "", .label, color: c.textSecondary)
                }
            }
            ZStack(alignment: singleLine ? .leading : .topLeading) {
                field
                    .font(WLTextStyle.button.font)
                    .foregroundStyle(c.text)
                    .tint(c.text)
                    .keyboardType(keyboard)
                    .accessibilityLabel(label ?? placeholder)
            }
            .padding(.horizontal, 18)
            .padding(.vertical, singleLine ? 0 : 16)
            .frame(maxWidth: .infinity, minHeight: 56, alignment: singleLine ? .leading : .topLeading)
            .background(face, in: RoundedRectangle(cornerRadius: WishlistTokens.Radius.m, style: .continuous))
        }
    }

    private var prompt: Text {
        Text(placeholder).font(WLTextStyle.buttonRegular.font).foregroundStyle(c.textSecondary)
    }

    @ViewBuilder private var field: some View {
        if singleLine {
            TextField("", text: limited, prompt: prompt)
        } else {
            TextField("", text: limited, prompt: prompt, axis: .vertical).lineLimit(minLines...)
        }
    }
}
