# QA-CLI-018: 카드를 끌 때 회전 중심(50%, 90%)이 SwiftUI에서만 어긋난 이유는?

> 확인 날짜: 2026-10-24 · C7 Task 6·7 · 구현: Android `feature/flow/ReviewCardStack.kt`, iOS `Features/Flow/ReviewCardStack.swift` · 기준: `design/handoff/interactions/motion.md` §4

**질문:** 보드는 카드에 `translateX(dx) rotate(dx/W·16°)`를 적용하고 `transform-origin: 50% 90%`를 둔다. Compose에서는 같은 값으로 바로 맞았는데 SwiftUI에서는 카드 위가 아니라 아래쪽까지 크게 흔들렸다. 무엇이 다른가?

**답변:** 두 플랫폼은 회전 중심을 잡는 기준 좌표가 다르다.

- **Compose `graphicsLayer`:** `translationX`·`rotationZ`·`transformOrigin`을 한 layer 행렬로 합친다. 회전은 layer 자신의 `transformOrigin`(카드의 50%/90%)을 기준으로 하고, 이동은 그 뒤에 더해진다. 그래서 CSS의 `translateX rotate`와 결과가 같다. 속성을 쓰는 순서는 결과에 영향을 주지 않는다.
- **SwiftUI modifier:** modifier는 안쪽부터 차례로 적용된다. `.offset(x:)`는 배치 frame을 옮기지 않고 그리는 위치만 옮긴다. 그래서 `.offset` 뒤에 `.rotationEffect(anchor:)`를 쓰면 anchor는 **옮기기 전 frame**의 90% 지점에 남는다. 카드는 이미 옆으로 가 있으므로 카드 밖의 점을 중심으로 돌게 된다.
- **고친 순서:** `.rotationEffect(angle, anchor: UnitPoint(x: 0.5, y: 0.9))`를 먼저(안쪽에), `.offset(x: dx)`를 나중에(바깥에) 둔다. 카드가 자기 중심으로 돈 뒤 함께 이동하므로 아래 가장자리는 거의 제자리에 있고 위쪽이 흔들린다. 나가는 카드(±480, ±16°)도 같은 순서다.
- **관련 함정(Android):** 끌기 속도를 재는 `pointerInput`을 `graphicsLayer`로 움직이는 카드 자체에 두면, 손가락 위치가 따라 움직이는 좌표계에서 측정되어 속도가 작게 나온다. 고정된 바깥 Box에 제스처를 두고 이동·회전은 안쪽 카드에만 적용한다.
