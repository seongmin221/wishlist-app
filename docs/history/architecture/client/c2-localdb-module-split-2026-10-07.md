# C2 SQLDelight를 `:localdb` 내부 모듈로 분리

> 2026-10-07 · C2 Task 7 · 상태: 확정

## 결정

SQLDelight plugin·schema(`Wishlist.sq`)·생성 코드를 새 Gradle 모듈 `:localdb`에 두고 `:shared`는 `implementation`으로만 의존한다(export 없음). C2 계획의 "`:shared` 한 모듈" 구조에서 벗어난 유일한 모듈 변경이다.

## 이유

- SQLDelight 생성 클래스(`WishlistDatabase`·query·row 타입)는 `public`으로만 생성된다. `:shared`에 두면 static `Shared.framework`의 ObjC header(`Shared.h`)에 SQL 내부 타입이 노출되어 Swift 공개 면이 넓어지고 SKIE 처리 대상이 늘어난다.
- `:localdb`로 분리하고 export하지 않으면 `SqlLocalStore`·`CachedGetItemRepository`·`DriverFactory`를 `:shared`의 `internal`로 유지할 수 있다. 링크한 `Shared.h`에서 SQLDelight 관련 심볼 0건을 확인했다.

## 영향

- static framework의 linker 옵션은 소비자에게 전달되지 않으므로 iOS 앱 target이 `-lsqlite3`를 직접 링크한다.
- `:localdb`가 iOS 프레임워크 binary를 불필요하게 선언하고, 패키지 `app.wishlist.shared.data.local`이 두 모듈에 걸쳐 있으며, `:shared`에 중복 `sqldelight.runtime` 선언이 있다(경미, 정리는 후속).
- 구조 설명은 [KMP 구조](../../../architecture/client/kmp.md#구현-구조)와 로컬 저장소 절을 따른다.
