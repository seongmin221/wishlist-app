# KMP 클라이언트 초기 셋업 구현 계획

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task in the current workspace.

**Goal:** Android·SwiftUI 앱이 UI 없는 KMP shared를 참조하는 개발 시작점을 만든다.

**Architecture:** `client/`는 서버와 독립된 Gradle build다. `android/`는 Compose application, `shared/`는 Android·iOS library, `ios/`는 SwiftUI Xcode project다.

**Tech Stack:** Kotlin 2.3.21, Gradle 9.3.0, AGP 9.0.0, JDK 17, Compose BOM 2026.02.01, activity-compose 1.12.4, SwiftUI.

**Spec:** [초기 셋업 설계](../../architecture/client/initial-setup.md)

## Global Constraints

- server 소스·설정 변경 없음. 초기 셋업 후 사용자의 후속 요청에 따라 client 변경과 관련 문서를 commit/push한다.
- Android compile/target 36, min 26; iOS deployment target 17.0은 사용자 확인을 받은 지원 하한이다.
- application ID `app.wishlist.android`, bundle ID `app.wishlist.ios`는 임시 값이다.
- shared에 UI dependency·서버 코드 dependency를 추가하지 않는다.
- full Xcode가 없는 환경에서는 iOS build/실행 성공을 주장하지 않는다.

## Review Focus

- 공백 포함 checkout 경로: Xcode script와 wrapper 경로를 인용한다.
- 로컬 SDK/JDK 경로: 기기별 절대 경로를 git에 넣지 않는다.
- iOS Debug/Release·simulator/device: Xcode 환경의 framework 경로를 사용한다.
- Android built-in Kotlin: legacy kotlin-android plugin과 함께 적용하지 않는다.
- 서버 워크스페이스 병행 작업: 변경 파일이 client와 관련 문서에 한정되는지 확인한다.

## Task 1: 독립 Gradle build와 Android 앱

**Files:** `client/settings.gradle.kts`, `client/build.gradle.kts`, `client/gradle.properties`, `client/gradle/libs.versions.toml`, `client/gradle/wrapper/*`, `client/gradlew*`, `client/shared/build.gradle.kts`, `client/shared/src/commonMain/kotlin/app/wishlist/shared/AppInfo.kt`, `client/android/build.gradle.kts`, `client/android/src/main/*`, `.gitignore`.

**Interfaces:** shared는 `AppInfo().displayName: String`을 제공한다. Android 진입 화면은 이 값을 사용한다.

- [x] 버전 카탈로그, checksum wrapper, 두 Gradle 모듈을 구성한다.
- [x] UI 없는 `AppInfo`와 이를 참조하는 최소 Compose 화면·시스템 테마를 만든다.
- [x] `cd client && ./gradlew :android:assembleDebug :android:lintDebug :shared:testAndroidHostTest`로 검증한다. Expected: BUILD SUCCESSFUL; 테스트는 기능이 없으므로 NO-SOURCE일 수 있다.

## Task 2: SwiftUI 앱과 문서

**Files:** `client/ios/Wishlist.xcodeproj/project.pbxproj`, shared scheme, `client/ios/Wishlist/*.swift`, `client/ios/scripts/build-shared.sh`, `client/README.md`, `docs/architecture/client/INDEX.md`, `docs/architecture/client/kmp.md`, `docs/superpowers/INDEX.md`.

**Interfaces:** SwiftUI도 `Shared.AppInfo().displayName`을 사용한다. Xcode script는 `:shared:embedAndSignAppleFrameworkForXcode`를 호출한다.

- [x] SwiftUI 진입점과 static framework search/link 설정을 만든다. script는 compile 이전에 실행하고 user script sandboxing을 끈다.
- [x] `plutil -lint client/ios/Wishlist.xcodeproj/project.pbxproj`, `sh -n client/ios/scripts/build-shared.sh`와 XML/프로젝트 참조 검사를 수행한다. Expected: 구문·참조 검사 통과.
- [x] Xcode가 있으면 simulator build, 없으면 제한과 실행 명령을 문서화한다.
- [x] architecture 문서·INDEX·실행 가이드를 업데이트하고 `git diff --check`와 변경 범위를 확인한다.

## 실행 기록

- 기존 `client/initial-setup` linked worktree에서 수행했다. 새 worktree는 만들지 않았다. 초기 셋업 검증 후 사용자가 같은 브랜치의 commit/push를 요청했다.
- Gradle wrapper를 9.3.0 task로 생성했고 distribution checksum을 고정했다.
- Android debug APK 생성·lint를 확인했다. shared Android host test는 테스트가 없는 `NO-SOURCE`이며 테스트 성공으로 집계하지 않았다.
- 아이콘 리소스 경로를 정리하는 동안 incremental resource merge가 기존 출력과 충돌했다. Android clean build로 리소스를 다시 생성한 뒤 APK와 lint를 재검증했다.
- Xcode project 구문·전체 object/source/scheme 참조와 Swift 구문을 검사했다. Gradle stub으로 build script의 공백 경로·JDK fallback·IDE guard를 확인했다.
- 별도 읽기 전용 리뷰에서 큰 결함은 발견되지 않았다. 아이콘·backup rule 보완도 추가 검토했다.
- Xcode 설치 후 26.6 / iOS SDK 26.5에서 후속 검증했다. x86_64 추가 링크 오류를 simulator architecture 제외 설정으로 수정했다. Debug/Release simulator 및 unsigned Release device build와 Debug 앱의 라이트·다크 실행을 확인했다. iPad 기본 방향 경고도 정리했다.
- 실제 기기 실행과 배포 서명은 검증하지 않았다. shared iOS test는 아직 테스트가 없어 compile/link `NO-SOURCE`, 실행 `SKIPPED`다.
- Studio 업데이트 후 2026.1.3의 IDE 로그에서 원본 로컬 저장소 client Gradle Sync 완료를 확인했다. APK·lint 재검증과 API 36 emulator 설치·실행, 라이트·다크 공통 문구 표시를 확인했다. Android 소스·AGP 버전 변경은 필요하지 않았다.
