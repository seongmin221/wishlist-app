# QA-CLI-007: 가장자리 끌어 뒤로를 SwiftUI 제스처가 아니라 window 수준 UIKit pan으로 만든 이유

> 2026-10-06 · C1 iOS 라우터 spike

## 질문

상세 화면 왼쪽 20pt에 SwiftUI `DragGesture` 띠를 올려 끌어서 뒤로를 만들면 안 되는가?

## 답변

iOS 17에서 동작하지 않았다. 상세에 가로 `ScrollView`(칩 줄)가 왼쪽 끝까지 있을 때, iOS 17.5에서는 띠가 위에 겹쳐 있어도 아래 `UIScrollView`의 pan이 터치를 가져가 뒤로가 시작되지 않았다. iOS 26.5에서는 띠가 이겼다. 원인은 추정이다. iOS 18부터 SwiftUI 제스처가 UIKit 인식기 위에서 돌아 겹친 순서를 따르지만, iOS 17은 그렇지 않은 것으로 보인다.

그래서 시스템 `interactivePopGesture`와 같은 구조를 쓴다. window에 `UIPanGestureRecognizer`를 달고,

- `shouldReceive`: 왼쪽 20pt 안 터치이고 뒤로 갈 스택이 있으며 전환·overlay가 없을 때만 받는다.
- `shouldBegin`: 오른쪽 가로가 우세할 때만 시작한다.
- `shouldBeRequiredToFailBy`: 스크롤 뷰와 다른 pan이 이 인식기의 실패를 기다리게 한다.

이 방식은 두 OS에서 같게 동작했고 가로·세로 스크롤과 충돌하지 않았다. 주의할 점: `cancelsTouchesInView = true`라 끌기가 시작되면 띠 안 컨트롤의 터치가 취소된다(탭은 pan을 시작하지 않아 영향 없음). 뷰가 사라지거나 window가 `nil`이 되면 인식기를 떼야 한다. `UIScreenEdgePanGestureRecognizer`는 시뮬레이터 주입 터치에 가장자리 정보가 없어 확인할 수 없었고, 실기기 손맛도 아직 확인하지 않았다.

## 근거

- [iOS 라우터 spike 결과](../../../history/architecture/client/ios-router-spike-2026-10-05.md)(결과 (b), 발견과 한계)
- [UIGestureRecognizerDelegate](https://developer.apple.com/documentation/uikit/uigesturerecognizerdelegate)
