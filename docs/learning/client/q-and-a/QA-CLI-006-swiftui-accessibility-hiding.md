# QA-CLI-006: SwiftUI에서 가려진 화면을 VoiceOver에서 빼는 방법

> 2026-10-06 · C1 iOS 라우터 spike와 overlay·라우터 구현

## 질문

시트나 상세 화면이 덮은 뒤 화면을 VoiceOver가 읽지 않게 하려고 층마다 `.accessibilityHidden(가려짐)`이나 `.accessibilityAddTraits(.isModal)`을 걸었더니 일부가 계속 읽히거나 탭 바까지 사라졌다. 왜 그런가?

## 답변

serve-sim `/ax` 접근성 트리로 iOS 17.5·26.5에서 확인한 동작이다.

- **조상의 `accessibilityHidden(false)`는 후손의 숨김을 취소한다.** 선택된 탭 층에 `.accessibilityHidden(false)`가 걸리면 그 아래에서 따로 `true`로 숨긴 가려진 목록·전환용 사진·장식 요소가 트리에 다시 나타났다. 그래서 값이 바뀌는 숨김을 조상 컨테이너에 걸지 않고, 가려지는 잎 층마다 건다. 예외로, `.accessibilityElement(children: .contain)` 뒤에 건 `accessibilityHidden(false)`는 후손 숨김을 취소하지 않았다.
- **`.ignore`만 쓰면 빈 요소가 남는다.** `.accessibilityElement(children: .ignore)`는 자식을 빼지만 라벨 없는 빈 요소 하나가 남는다. `.ignore` + `accessibilityHidden(true)`면 완전히 빠진다. 이 두 경우를 묶은 것이 `wlAccessibilityCovered(covered)`다. 가려지면 `.ignore` + `hidden(true)`, 보이면 `.contain` + `hidden(false)`이고, 같은 modifier에서 값만 바뀌어 뷰 정체성도 유지된다.
- **`.isModal`은 형제를 모두 뺀다.** 상세에 `.isModal`을 걸면 형제인 탭 바까지 트리에서 빠져, 탭 바를 유지하는 화면에서 탭을 바꿀 수 없었다.
- **자식이 하나인 `.contain` 컨테이너는 접힌다.** 칸 컨테이너에 건 `.accessibilityAction(.escape)`가 전달 경로에서 빠졌다. 보이지 않는 형제를 하나 더 두자 escape(두 손가락 문지르기)가 칸 컨테이너에서 처리됐다.

시뮬레이터에는 VoiceOver가 없어 escape는 `accessibilityContainer`를 따라 `accessibilityPerformEscape()`를 보내는 방식으로 흉내 냈다. 실제 VoiceOver의 포커스 이동과 문지르기는 실기기 확인이 남아 있다.

## 근거

- [iOS 라우터 spike 결과](../../../history/architecture/client/ios-router-spike-2026-10-05.md)(발견과 한계, Task 7 구현에서 보탠 사실)
- [accessibilityHidden(_:)](https://developer.apple.com/documentation/swiftui/view/accessibilityhidden(_:)), [accessibilityElement(children:)](https://developer.apple.com/documentation/swiftui/view/accessibilityelement(children:))
