import SwiftUI

/// 그 자리 편집 칸. 1pt 밑줄(평소 `underline` 회색, 포커스면 글자색)이 한글 아래 끝에서 3pt 아래에 그려진다.
/// `wlFieldText`와 `wlUnderlined`를 올바른 순서로 여기서 함께 적용하므로 호출하는 쪽은 신경 쓰지 않는다.
/// `underlineColor`로 색 면 위 값(먹색 35%)을 줄 수 있다. 밑줄이 상자 아래로 나오니 부모는 clip하지 않는다.
/// `editable == false`면 같은 크기의 투명 밑줄로 보기 상태를 그려 글자 위치가 바뀌지 않는다.
struct WLUnderlineField: View {
    @Binding var value: String
    var style: WLTextStyle = .display28Edit
    var editable = true
    var placeholder = ""
    var underlineColor: Color?

    @Environment(\.wlColors) private var c
    @FocusState private var focused: Bool

    var body: some View {
        let line: Color = !editable ? .clear : (focused ? c.text : (underlineColor ?? c.underline))
        TextField("", text: $value, prompt: Text(placeholder).foregroundStyle(c.textSecondary))
            .focused($focused)
            .disabled(!editable)
            .foregroundStyle(c.text)
            .tint(c.text)
            .wlFieldText(style)
            .wlUnderlined(style, color: line)
    }
}
