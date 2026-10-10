# C4 Android 상세·분석 중·로컬 대기 화면 확인 (2026-10-10)

> C4 Task 10. 에뮬레이터 `emulator-5554`(API 36, 앱 문구 한국어), DEBUG Fake. 캡처는 [c4-shots](c4-shots/)(1200px로 줄임). 비교 기준은 보드 `FProductDetail{L,D}`·`FProductProcessing{L,D}`와 [C4 설계](../../../superpowers/specs/2026-10-09-client-c4-product-detail-design.md) §3 D8·D9·D12.

## 확인한 흐름

| 흐름 | 결과 | 캡처 |
| --- | --- | --- |
| 로그인 뒤 "분류 중" 줄 → 상세(분석 중) | 가로 밀기, 탭 바 없음. 222 카드 + 분석 중 타일 + "상품 정보 추출 중", host 제목, 안내, "방금 저장" | `android-processing-dark-justnow.png` |
| 5초 뒤 foreground(화면 끄고 켜기) → 상세 당겨서 새로고침 → 상세(READY) | 같은 화면이 상세로 바뀜. 사진 없음 → 자리표시, 브랜드·가격 없음 → 줄 숨김, 카테고리 "신발", 목적 미지정, "오늘 저장" | `android-ready-dark.png`, `android-ready-light.png` |
| (리뷰 수정 뒤) 분석 중 상세 → 6초 뒤 당겨서 새로고침만 | DEBUG runtime이 상세 GET 앞에 Fake 분석을 진행해 같은 화면이 바로 상세로 바뀜(D7) | `android-pull-before-processing-dark.png`, `android-pull-after-ready-dark.png` |
| ITEM-01 15초 지연 + 대기 1개 → 줄 → 로컬 대기("보내는 중") → 전송 완료 | 대기 타일 "보내는 중", "방금 저장 · 이 기기에만 있어요", ⋯ 있음. 응답 뒤 `replaceTop`으로 분석 중 상세로 교체(cross-fade) | `android-local-sending-light.png`, `android-movedto-processing-light.png` |
| 로그아웃 → 대기 1개 → "분석 대기" 줄 → 로컬 대기 → ⋯ → 삭제 → 확인 | "분석 대기" 타일, ⋯ 메뉴 "삭제", D8 확인창(대상 줄 + 글머리표 2 + 취소·삭제 빨강). 삭제 뒤 홈으로 닫히고 줄이 사라짐 | `android-local-pending-light.png`, `android-local-menu-light.png`, `android-delete-dialog-{light,dark}.png` |

## 보드와 다른 점

- 분석 중·완료 상세의 ⋯가 없다(D2, C8에서 붙임). 보드에는 있다.
- 보드는 버튼 top 52·좌우 16인데 2026-10-07 결정(안전 영역 + 12, 좌우 20)을 따랐다.
- (리뷰 수정 뒤 같음) 완료 상세의 사진은 보드·2026-10-04 결정처럼 1:1 칸(모서리 20) 안 여백 56에 원래 비율로 넣는다(`ProductPhoto(contentScale = Fit, imagePadding = 56.dp)`, 자리표시는 칸 전체). Fake·seed 상품에 사진 주소가 없어 실제 사진 캡처는 없다(자리표시만 확인).
- 정보 보완 안내(INCOMPLETE)는 보드 FProductFill의 경고 카드 모양에 "다시 분석" 버튼 없이 안내 한 줄과 host만 둔다(D10, 보완은 C8).

## 확인하지 못한 것

- **로그아웃 시 상세 닫힘:** 설정은 홈 탭 첫 화면에서만 열리고 상세가 그 위를 덮어, 상세가 스택에 있는 채로 로그아웃할 UI 경로가 없다. 정리 규칙은 Task 7 navigator 테스트로 검증되어 있다.
- **로그인 뒤 로컬 삭제:** 로그인 상태에서는 대기 줄이 바로 전송되어 PENDING으로 남지 않는다(네트워크를 끄는 조작은 하지 않았다). 로그인 전 삭제만 확인했다.
- **목적 색 점:** Fake 분석 결과는 목적이 없고 목적이 있는 seed 상품은 C4 홈에서 들어갈 길이 없다(C5 목록). 표시값은 `FakeItemRepositoryTest`로, 색 매핑은 `DetailTextTest`로 확인했다.
- 영어(en-US) 문구 화면은 찍지 않았다(앱이 한국어로 떴다). 키는 JVM 테스트와 `values-en`으로 확인.
