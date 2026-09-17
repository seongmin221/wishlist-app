# Client 구조

> 상태: **확정** — UI는 platform-native, 공통 비즈니스 계층은 Kotlin Multiplatform(KMP)으로 유지한다.

## 경계

| 위치 | 담당 |
| --- | --- |
| iOS | Swift, SwiftUI, Share Extension, iOS 보안 저장소·알림·앱 생명주기 |
| Android | Kotlin, Jetpack Compose, `ACTION_SEND` 수신, Android 보안 저장소·알림·앱 생명주기 |
| KMP | Domain model, Repository, UseCase, API client, local DB/cache, sync, URL normalization |

공유 계층은 UI 프레임워크를 알지 않아야 한다. SwiftUI와 Compose는 각 플랫폼의 화면 상태와 사용자 상호작용만 담당하고, 저장·동기화 결과는 공통 UseCase를 통해 받는다.

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
