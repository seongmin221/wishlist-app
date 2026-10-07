# QA-CLI-004: 시트·확인창을 시스템 컴포넌트 대신 직접 그리는 이유

> 2026-10-06 · C1 디자인 시스템 overlay 구현

## 질문

Compose `ModalBottomSheet`·`AlertDialog`나 SwiftUI `.sheet`·`.alert`가 있는데, 왜 시트·확인창·메뉴를 `OverlayHost`에서 직접 그리는가?

## 답변

모션 명세와 화면 구성을 시스템 컴포넌트로는 맞출 수 없기 때문이다.

- **모션:** 시트는 480ms `spring-sheet`로 약 6% 지나쳤다가 돌아오고, 막(scrim)은 400ms 동안 뒤 화면을 12px 흐리게 하며 어둡게 한다. 닫기는 260ms accelerate다. 시스템 시트는 시간·곡선·블러를 정할 수 없다. iOS `.sheet`는 뒤 화면을 줄이는 시스템 표현을 쓰고, Android `ModalBottomSheet`는 자체 애니메이션과 막 색을 쓴다.
- **창 분리:** 시스템 시트·다이얼로그는 별도 창(Android `Dialog` window, iOS 별도 presentation)에 뜬다. 그러면 시트 위 확인창의 추가 어둠, 메뉴 → 확인창 연결, 공유 요소 전환과의 층 순서를 한 화면 안에서 맞추기 어렵고, 테마가 바뀌는 순간 내용과 색이 따로 움직인다.
- **연타 규칙:** 제품은 열고 닫는 중의 뒤로·막 누르기 연타를 무시하고, 닫는 중에 들어온 열기는 하나만 줄 세워야 한다. 직접 만든 상태 기계(`Opening → Open → Closing`)는 이 규칙을 단위 테스트로 고정할 수 있다.

대신 시스템이 주던 것을 직접 챙겨야 한다: 뒤로 처리 우선순위(Android는 overlay `BackHandler`를 내용 뒤에 등록), 입력 차단, 접근성(가려진 내용 숨김, escape로 닫기), 키보드 회피. 키보드와 하드웨어 Esc는 C1에서 확인하지 않았다.

## 근거

- [motion.md 1절(시트)](../../../../design/handoff/interactions/motion.md), [디자인 결정](../../../design/decisions.md)
- [디자인 시스템과 앱 뼈대 — overlay](../../../architecture/client/design-system.md#overlay)
