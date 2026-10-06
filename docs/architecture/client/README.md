# Client 구조

> 상태: **확정** — UI는 platform-native, 공통 비즈니스 계층은 Kotlin Multiplatform(KMP)으로 유지한다.

## 경계

| 위치 | 담당 |
| --- | --- |
| iOS | Swift, SwiftUI, Share Extension, iOS 보안 저장소·알림·앱 생명주기 |
| Android | Kotlin, Jetpack Compose, `ACTION_SEND` 수신, Android 보안 저장소·알림·앱 생명주기 |
| KMP | Domain model, Repository, UseCase, API client, local DB/cache, sync, URL normalization |

공유 계층은 UI 프레임워크를 알지 않아야 한다. 화면의 비즈니스 상태(데이터, 로딩·오류, 편집 초안, 연속 처리 진행, 충돌 복구)는 KMP Presenter가 소유한다. SwiftUI와 Compose는 Presenter를 감싼 얇은 래퍼(Android `ViewModel`, iOS `@Observable`)로 그 상태를 그리고, 시트 열림·애니메이션·스크롤 측정 같은 순수 UI 상태와 내비게이션·공유 요소 전환만 직접 다룬다.

## 구현 진행 방식

서버 API가 묶음 순서로 늘어나는 동안 클라이언트는 서버 계약을 먼저 옮겨 쓰고 KMP fake로 화면을 완성한다. 서버 묶음이 끝나면 실제 API로 바꾼다. 기능 단계마다 KMP → Android → iOS를 함께 끝낸다. 단계 구성과 완료 기준은 [구현 로드맵 설계](../../superpowers/specs/2026-10-05-client-implementation-roadmap-design.md), 결정 근거는 [ADR-027](../../history/architecture/client/ADR-027-client-implementation-strategy.md)을 따른다.

## 공통 웹뷰 구현 책임

웹뷰의 사용자 동작은 [상품 확인과 편집의 원본 링크 웹뷰](../../product/inspect-and-edit-a-product.md#원본-링크-웹뷰)를 따른다. iOS와 Android adapter는 각 platform-native 웹뷰의 생성·수명주기·상태 복원과 navigation, 쿠키·웹뷰 데이터, 외부 intent 연동을 구현하고, 공통 계층에는 제품 결정 상태를 바꾸지 않는 탐색 상태만 전달한다.

## 공통 저장 상태

- 공유 URL을 수신하면 서버 상품과 구분되는 `LocalSubmission`을 우선 만든다.
- 한 번의 공유에 생성한 `clientSubmissionId`를 재전송에도 유지한다. 서버가 항목을 생성하면 응답을 로컬 캐시에 반영한 뒤 LocalSubmission을 제거한다.
- MVP에서는 push, realtime과 주기적 polling을 사용하지 않는다. 앱 신규 실행, foreground 복귀와 사용자의 새로고침 시 서버 상태를 한 번 조회한다.
- 앱 신규 실행은 최신 첫 window를 조회한다. foreground 복귀와 새로고침은 현재 anchor 상품 주변 앞뒤 20개를 갱신하고 stable item ID와 카드 내부 offset으로 위치를 유지한다.
- 서버가 홈 조치 상태를 계산하고 KMP는 기기의 LocalSubmission을 `분석 대기` 항목으로 합성한다.
- 상세 상태와 API 계약은 [WishlistItem 상태 모델과 API 계약](../wishlist-item-state-api.md)을 따른다.

- [iOS 구조](ios.md)
- [Android 구조](android.md)
- [KMP 구조](kmp.md)

## 미결정 사항

- 로그인 상태에서 iOS Share Extension·Android 공유 수신 Activity가 공유된 로그인 토큰으로 직접 서버 저장 요청을 보낼지, 로컬에만 `LocalSubmission`을 만들고 전송을 앱에 맡길지 정하지 않았다. 공유 직후 확인 카드의 문구가 이 결정에 따라 달라진다. 배경은 [QA-CLI-002](../../learning/client/q-and-a/QA-CLI-002-share-receipt-feedback.md)를 참고한다.
