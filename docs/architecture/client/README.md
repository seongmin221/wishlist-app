# Client 구조

> 상태: **확정** — UI는 platform-native, 공통 비즈니스 계층은 Kotlin Multiplatform(KMP)으로 유지한다.

## 경계

| 위치 | 담당 |
| --- | --- |
| iOS | Swift, SwiftUI, Share Extension, iOS 보안 저장소·알림·앱 생명주기 |
| Android | Kotlin, Jetpack Compose, `ACTION_SEND` 수신, Android 보안 저장소·알림·앱 생명주기 |
| KMP | Domain model, Repository, UseCase, API client, local DB/cache, sync, URL normalization |

공유 계층은 UI 프레임워크를 알지 않아야 한다. SwiftUI와 Compose는 각 플랫폼의 화면 상태와 사용자 상호작용만 담당하고, 저장·동기화 결과는 공통 UseCase를 통해 받는다.

## 공통 웹뷰 동작

- 상품 상태와 관계없이 원본 링크는 platform-native 웹뷰로 연다.
- 웹뷰는 앱 navigation 안에서 열고, 닫으면 이전 화면 상태로 돌아간다.
- iOS와 Android 모두 웹페이지 방문 기록을 우선해 뒤로 가고, 기록이 없으면 웹뷰를 닫는다.
- 새 창 요청은 현재 웹뷰에서 처리한다.
- 사용자가 직접 누른 외부 앱 링크는 허용하고, 자동 외부 앱 실행은 확인을 받는다.
- 쿠키는 방문 간 유지하며 설정에서 웹뷰 데이터를 삭제할 수 있게 한다.
- 외부 앱에서 복귀하면 기존 웹뷰를 유지하고 강제 새로고침하지 않는다.
- 웹뷰는 결제 완료를 감지해 제품의 구매 결정 상태를 자동으로 바꾸지 않는다.

## 공통 저장 상태

- 공유 URL을 수신하면 우선 local pending item을 만든다.
- 서버가 항목을 생성하면 서버 ID와 처리 상태를 반영한다.
- 목록 갱신으로 `PROCESSING`, `READY`, `PARTIAL`, `FAILED` 상태를 동기화한다.
- 초기에는 앱 진입/목록 refresh 기반 polling을 사용하고, push/realtime은 후속 검토한다.

- [iOS 구조](ios.md)
- [Android 구조](android.md)
- [KMP 구조](kmp.md)
