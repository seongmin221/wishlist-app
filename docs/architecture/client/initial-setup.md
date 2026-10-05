# KMP 클라이언트 초기 셋업

> 2026-10-05 · 범위: 독립 빌드와 native 앱 진입점

## 목적과 범위

이 워크스페이스(`client/initial-setup` 브랜치)는 iOS·Android 클라이언트 개발을 시작한다. 서버는 별도의 `server/initial-setup` 워크스페이스에서 진행 중이며, 이번 작업은 `server/`의 소스·빌드 설정을 변경하지 않는다.

이미 확정된 [공통 경계](README.md)와 [repository 구조](../repository-structure.md)를 따른다. 초기 셋업의 완료 기준은 Android 앱이 공통 Kotlin 코드를 참조해 빌드되고, SwiftUI 앱에 같은 코드의 framework 연결이 구성되는 것이다. 전체 MVP 화면 구현을 셋업 완료 조건으로 삼지 않는다.

## 검토한 제품·디자인

- [제품 개요](../../product/overview.md), [상품 저장](../../product/save-a-product.md), [구매 후보 정리](../../product/organize-candidates.md), [상품 확인과 편집](../../product/inspect-and-edit-a-product.md), [구매 결정 종료](../../product/finish-a-purchase-decision.md)
- [상품 상태](../../product/references/item-states.md), [상태/API 계약](../wishlist-item-state-api.md)
- [디자인 결정](../../design/decisions.md), [인터랙션과 애니메이션](../../../design/handoff/interactions.md), [화면 생성기](../../../design/canvas-fresh/README.md)

핸드오프가 참조하는 `design/handoff/screens/`와 `wireframes/`는 이 체크아웃에 없다. 현재 확보된 화면 자료는 `design/canvas-fresh/` 생성기와 디자인 결정이다. 없는 PNG/HTML을 검토했다고 간주하지 않는다.

후속 구현에서 유지할 조건:

- UI는 SwiftUI와 Jetpack Compose, shared는 UI에 의존하지 않는다.
- 하단 탭은 홈·카테고리·목적이다. 아카이브는 목적 안, 설정은 홈에서 연다.
- 시스템 라이트·다크를 따르고, 목적의 색과 처리 상태 표시를 구분한다.
- 로컬 대기 항목은 서버 `WishlistItem`과 분리하며 계정 귀속과 재전송 ID를 보존한다.
- 홈 조치 영역과 추출 상태·검토 상태는 서로 다른 축이다.
- 상태 갱신은 신규 실행·foreground·새로고침 시 수행한다. 주기적 polling은 도입하지 않는다.
- 아카이브는 목적과 후보 전체의 스냅샷이며 부분 복원·부분 삭제하지 않는다.
- 사진 공유 전환, 자리 표시 면 전환, 시트와 연속 처리 모션은 native 화면 구현 단계에서 반영한다.

## 구조와 선택

```text
client/                  # server와 독립된 Gradle build
  android/               # Android application, Compose
  shared/                # Kotlin Multiplatform library, UI 없음
  ios/                   # SwiftUI app과 Xcode project
  gradle/libs.versions.toml
```

공유 UI 방식은 확정된 native UI 경계와 맞지 않는다. 플랫폼별로 비즈니스 코드를 중복 구현하는 방식은 기존 KMP 결정과 맞지 않는다. 따라서 native 앱 두 개와 작은 shared 모듈로 시작한다. 세부 domain/data 모듈, DI, DB, HTTP, 인증 SDK는 실제 기능을 도입할 때 추가한다.

Kotlin 2.3.21, AGP 9.0.0, Gradle 9.3.0, JDK 17을 사용한다. 서버 Kotlin 버전은 참고하되 서버 Gradle wrapper 버전을 그대로 재사용하지 않는다. Android compile/target SDK는 36이다. 지원 하한은 사용자 확인에 따라 Android API 26 / iOS 17.0으로 정했다. 임시 application/bundle ID는 `app.wishlist.android` / `app.wishlist.ios`다.

`shared`는 공식 Android KMP library plugin과 `iosArm64`, `iosSimulatorArm64`를 선언한다. iOS는 static `Shared.framework`를 Xcode build phase의 `embedAndSignAppleFrameworkForXcode`로 연결한다. CocoaPods/SPM 배포는 현재 필요하지 않다. Android는 AGP 내장 Kotlin을 사용한다.

진입 화면은 공통 `AppInfo`를 참조하는 최소 화면이다. 실제 홈·로그인 화면이나 저장 기능을 구현한 것으로 표시하지 않는다. 디자인의 바탕·글자 색만 플랫폼 테마에 반영하고 커스텀 서체·모션은 화면 구현에서 적용한다.

Android launcher는 핸드오프의 코랄 면·먹색 체크를 native vector/adaptive icon으로 구성했다. 로컬 대기 항목이 재설치 후 복구되지 않는 제품 규칙에 맞춰 `allowBackup=false`를 설정하고 Android 12+ cloud backup·device transfer에서 앱 데이터 domain을 제외했다. 아직 local DB는 없으며, 이후 저장소 도입 시 이 정책을 유지한다. iOS 저장소의 backup 제외는 실제 저장소 구현 시 설정한다. Android 정책 근거는 [Auto Backup 공식 문서](https://developer.android.com/identity/data/autobackup)를 따른다.

## 검증과 제한

- Gradle wrapper checksum을 고정한다.
- Android debug APK, lint와 shared Android compilation으로 공통 연결을 검증한다.
- Xcode project와 scheme을 구조 검사하고 Xcode가 있는 환경에서 simulator build를 수행한다.
- 현재 기기는 JDK 17, Android SDK 36이 있지만 full Xcode는 없다. iOS 링크·실행 검증을 했다고 보고하지 않는다.
- 초기 화면·상수와 설정을 그대로 복제하는 단위 테스트는 만들지 않는다. 공통 비즈니스 규칙을 추가할 때 `commonTest`에서 검증하고 Android host/iOS simulator에서 실행한다.

Xcode build script는 공백 포함 임시 경로와 Gradle stub으로 호출 위치·task 이름·JDK 17 fallback·IDE 중복 실행 방지 guard를 확인했다. 이 검증은 실제 Xcode framework 빌드의 대체물이 아니다. full Xcode 환경에서 Debug/Release 및 simulator/device 연결을 확인하는 일이 남아 있다.

2026-10-05 확인 결과:

| 검사 | 결과 |
| --- | --- |
| Android clean debug build | `BUILD SUCCESSFUL`, APK 생성 |
| Android lint | 오류 0, dependency 새 버전 알림 7개 |
| shared Android compilation | 성공 |
| shared Android host test | `NO-SOURCE` — 아직 테스트 없음 |
| Xcode project·scheme·source 참조, Swift parse, shell syntax | 통과, 링크·실행 검증과 구분 |
| 서버 변경 | 없음 |

## 후속 설계

공유 수신 시 직접 서버 전송 여부, 인증 공급자, local DB·HTTP·Swift 비동기 bridge는 아직 구현 선택이다. 특히 공유 수신 확인 문구는 실제 저장 경로가 결정된 뒤 연결한다. 셋업에서 임의의 서버 DTO나 테스트 계정을 만들지 않는다.

## 공식 근거

- [KMP 버전 호환성](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)
- [Android KMP library plugin](https://developer.android.com/kotlin/multiplatform/plugin)
- [AGP 9.0 호환성과 내장 Kotlin](https://developer.android.com/build/releases/agp-9-0-0-release-notes)
- [Xcode direct integration](https://kotlinlang.org/docs/multiplatform/multiplatform-direct-integration.html)
