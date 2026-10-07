# QA-CLI-005: Compose 한 줄 글자의 줄 높이가 줄지 않는 이유와 Android 14 글자 배율

> 2026-10-06 · C1 서체·글자 스타일 구현

## 질문

1. `display28Edit`(Do Hyeon 28, 줄 높이 24px)에 `lineHeight = 24.sp`를 줬는데 Android에서 한 줄 칸이 24dp가 아니라 28dp가 넘는 이유는?
2. 밑줄 간격 보정을 sp 차이로 계산했더니 글자 크기 1.3배에서 어긋나는 이유는?

## 답변

1. Compose `Text`는 줄 높이를 글꼴 고유 높이(ascent + descent)보다 작게 만들지 않는다. 에뮬레이터 측정에서 `LineHeightStyle.Trim.Both`면 한 줄 칸이 `고유 높이 + (줄 수 − 1) × lineHeight`가 되어 lineHeight 20·28·40 모두 한 줄은 28.2dp였다. `Trim.None`은 늘리기만 한다. 그래서 `includeFontPadding = false` + `LineHeightStyle(Center, Trim.Both)`를 두고, `Modifier.wlLineBox(style)` layout modifier가 `lineHeight − 고유 높이`만큼 칸을 줄이거나 늘리고 글자를 절반 옮긴다. 이 modifier는 Text 바로 앞(가장 안쪽)에 둬야 배경·밑줄이 보정된 칸을 기준으로 그려진다. iOS는 `lineSpacing`이 한 줄 칸을 바꾸지 못하고 음수도 받지 않아 다른 방법을 쓴다([QA-CLI-008](QA-CLI-008-swiftui-line-height-below-font.md)).
2. Android 14(API 34)부터 글자 크기 배율이 sp에 비선형으로 적용된다. 큰 글자일수록 덜 커져서 28sp는 배율 1.3에서 약 1.03배만 커진다. 따라서 `(a − b).sp.toPx()`는 `a.sp.toPx() − b.sp.toPx()`와 다르다. 글자 크기·줄 높이처럼 sp인 값은 각각 px로 바꾼 뒤 빼고, 고정 간격(밑줄 3px처럼 dp로 정한 값)만 dp로 둔다. 이렇게 바꾼 뒤 배율 1.0·1.3 모두 한글 아래 끝과 밑줄 사이가 3.04–3.05dp로 측정됐다.

## 근거

- [Android 14 비선형 글꼴 배율](https://developer.android.com/about/versions/14/features#non-linear-font-scaling)
- [Compose `LineHeightStyle`](https://developer.android.com/reference/kotlin/androidx/compose/ui/text/style/LineHeightStyle)
- [디자인 시스템과 앱 뼈대 — 서체와 글자 스타일](../../../architecture/client/design-system.md#서체와-글자-스타일)
