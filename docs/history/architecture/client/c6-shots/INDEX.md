# C6 화면 확인

[구현·검증 기록](../c6-purpose-implementation-2026-10-24.md)의 화면 근거다. 보드 원본은 `design/handoff/screens/shots/FPurpose*{L,D}.png`다. 픽셀 자동 비교가 아니라 보드와 나란히 보는 육안 비교이며, 기기 안전 영역·논리 크기는 보드 390×844와 다르다.

- Android: Pixel 5 API 30 에뮬레이터, ko-KR, `android-*-L/D.png`. 목적 탭·상세·접힘·생성·편집·빈 상세·버리기 확인, 생성 시트 IME footer를 확인했다. 작은 표시 크기·긴 설명 보완 화면도 함께 보존한다. `android-small-edit-ime-D.png`·`android-small-saved-D.png`는 작은 표시 크기에서의 입력·저장 근거다.
- iOS: iPhone 17 Pro / iOS 26.2 시뮬레이터, 한국어, `ios-purpose-*-light/dark.png`. 목적 탭·만들기·상세·접힘·편집·빈 상세를 확인했다. 편집 패널을 자르던 시트 clip 위치 결함을 수정한 뒤 편집 화면을 다시 캡처했다.
- Fake 시드는 목적 순서 출퇴근→러닝→의자→캠핑→조명→선물→여행이며 실제 후보 수는 `5/3/2/4/3/2/0`이다. 일부 상품 사진이 null이므로 중립 placeholder가 보인다. 보드 삽화와 같은 사진을 공급한 것은 아니다. 아카이브 수0은 B3 계약을 따른다.
- 후보 추가·삭제·종료·아카이브는 비활성 진입점이다. 상세의 명시적 새로고침 컨트롤과 긴 설명·작은 화면의 입력/저장 접근성은 구현 보완이다.
