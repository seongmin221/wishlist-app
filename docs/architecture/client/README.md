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

- 공유 URL을 수신하면 우선 local pending item을 만든다.
- 서버가 항목을 생성하면 서버 ID와 처리 상태를 반영한다.
- 목록 갱신으로 `PROCESSING`, `READY`, `PARTIAL`, `FAILED` 상태를 동기화한다.
- 초기 제안으로는 앱 진입·목록 refresh 기반 polling을 사용한다. 이는 확정된 전달 방식이 아니며, polling·realtime·push 중 최종 선택은 [Server 구조의 기술 미결정 사항](../server/overview.md#기술-미결정-사항)에서 별도로 결정한다.

- [iOS 구조](ios.md)
- [Android 구조](android.md)
- [KMP 구조](kmp.md)
