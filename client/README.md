# Wishlist client

iOS는 SwiftUI, Android는 Jetpack Compose, 공통 비즈니스 계층은 KMP로 구성한다. 서버는 독립적인 `server/` build이며 이 build에 포함하지 않는다.

## 현재 범위

C1은 디자인 토큰 생성기, 서체·글자 스타일, 공통 컴포넌트, 시트·확인창·메뉴 overlay, 탭 셸과 자체 라우터(공유 요소 화면 이동, 끌어서 뒤로)를 두 앱에 구현했다. 탭 첫 화면은 debug 빌드에서 이것들을 보여 주는 데모다. C2는 화면 없이 공통 KMP 핵심(상품 모델·상태 정책·가격 표기, Fake/Remote(ITEM-01·03)·계정별 SQLDelight 캐시, API별 backend 조립 `SharedRuntime`, 상품 상세 Presenter 기반)을 구현했다. C3는 첫 실제 기능인 공유 저장을 두 앱에 구현했다: Android 공유 Activity·iOS Share Extension(app group inbox), fake 로그인(실제 Firebase 연결 전), 로그인 전 홈·분석 대기, 로그인 뒤 "분류 중" 카드, 설정(로그아웃·웹뷰 데이터 삭제). 상품 상세·카테고리·목적 등 나머지 기능 화면은 아직 없다. 구조는 [디자인 시스템과 앱 뼈대](../docs/architecture/client/design-system.md)와 [KMP 구조](../docs/architecture/client/kmp.md)를 따른다.

| 위치 | 역할 |
| --- | --- |
| `android/` | Compose application, AGP 내장 Kotlin |
| `shared/` | Android·iOS Kotlin library, UI 의존성 없음 |
| `localdb/` | SQLDelight plugin·schema·생성 코드만 담은 내부 모듈(`:shared`가 `implementation`으로 사용, Swift에 노출하지 않음) |
| `ios/` | SwiftUI app과 `ShareExtension` target(`AppGroupShared/`는 앱·확장 공용), Xcode project와 공유 scheme(`WishlistTests` 단위 테스트 포함) |
| `tools/` | 디자인 토큰 원본(`design-tokens.json`)과 생성기 |
| `gradle/libs.versions.toml` | 클라이언트 dependency·plugin 버전 |

## 개발 환경

- Android Studio **Otter 3 Feature Drop 2025.2.3 이상** — AGP 9.0을 지원하는 버전이 필요하다. 최신 안정판 권장. Narwhal 2025.1.1의 AGP 지원 상한은 8.11이므로 현재 프로젝트를 sync할 수 없다.
- JDK 17
- Android SDK Platform 36 / Build Tools 36.0.0
- Android 8(API 26)+, iOS 17+
- iOS 개발: full Xcode와 iOS simulator runtime, Apple Silicon Mac. 이 기기에서는 Xcode 26.6 / iOS 26.5 SDK로 빌드·실행을 확인했다.
- Gradle 9.3.0은 wrapper가 내려받고 SHA-256을 검증한다. 별도 Gradle 설치는 필요 없다.

Kotlin 2.3.21, AGP 9.0.0을 사용한다. iOS framework target은 `iosArm64`와 `iosSimulatorArm64`이며 Intel simulator는 현재 구성하지 않았다. 임시 ID는 Android `app.wishlist.android`, iOS `app.wishlist.ios`다. 출시 전 소유한 ID와 서명 설정을 정한다.

## Android 실행

Android Studio에서 repository root 대신 **`client/`**를 연다. SDK 설정은 IDE가 만드는 `local.properties` 또는 `ANDROID_HOME`을 사용하며 기기별 경로를 commit하지 않는다.

`Latest supported version is AGP 8.11.0` 오류가 나면 Studio 버전을 확인한다. macOS에서는 **Android Studio → Check for Updates**에서 업데이트하거나 [공식 다운로드](https://developer.android.com/studio)를 사용한다. 업데이트 후 `client/`를 다시 열고 Gradle Sync를 실행한다. AGP·Gradle·Kotlin 버전만 개별적으로 낮추지 않는다. [공식 Studio/AGP 호환 표](https://developer.android.com/studio/releases#android_gradle_plugin_and_android_studio_compatibility)를 기준으로 전체 빌드 설정을 함께 검토해야 한다.

CLI에서 macOS 기본 SDK 경로를 사용할 경우:

```sh
cd client
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :android:assembleDebug :android:lintDebug :shared:testAndroidHostTest
```

APK: `android/build/outputs/apk/debug/android-debug.apk`. 연결된 emulator/기기에 설치하려면 `./gradlew :android:installDebug`를 실행한다.

`shared`의 공통 테스트는 `shared/src/commonTest/kotlin/`에 있고 Android host와 iOS simulator에서 같은 suite를 실행한다.

2026-10-05 Android Studio 2026.1.3으로 업데이트한 뒤 원본 로컬 저장소의 `client/` Gradle Sync 성공을 Studio 로그에서 확인했다. APK build·lint를 재검증했고 `Medium_Phone_API_36.0` emulator에서 설치·실행과 공통 코드 문구의 라이트·다크 표시를 확인했다. AGP 버전은 9.0.0을 유지한다. 최소 지원 API 26의 실제 실행은 이번 검증에 포함하지 않았다.

## iOS 실행

full Xcode를 설치하고 아래 명령으로 현재 터미널에서 사용할 Xcode를 지정한다. Command Line Tools만으로 Kotlin/Native framework나 SwiftUI 앱을 빌드할 수 없다. Xcode 앱에서 실행하는 build phase는 Xcode의 개발 도구 경로를 전달받는다.

```sh
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xcodebuild -runFirstLaunch
open client/ios/Wishlist.xcodeproj
```

`Wishlist` scheme에서 iOS 17 이상 **arm64 simulator**를 선택해 실행한다. 실제 기기에서는 Signing & Capabilities에서 개인 Team을 설정해야 한다. 개발자 Team ID는 repository에 포함하지 않았다.

시뮬레이터 빌드의 Debug/Release 설정에서 x86_64를 제외한다. shared가 `iosSimulatorArm64`만 제공하므로 Intel용 앱 바이너리를 함께 링크하지 않는다. iPad에서도 사용할 수 있도록 기본 화면 회전은 네 방향을 지원한다.

Xcode의 `Build Shared` phase는 Swift compile 전에 `ios/scripts/build-shared.sh`를 실행한다. 이 스크립트가 `:shared:embedAndSignAppleFrameworkForXcode`로 static `Shared.framework`를 만들고 Xcode 설정에 맞는 경로로 복사한다. 첫 실행은 Kotlin/Native toolchain 다운로드로 시간이 걸릴 수 있다. CocoaPods/SPM 설치는 필요 없다.

서명 없는 simulator build 확인:

```sh
cd client
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xcodebuild -project ios/Wishlist.xcodeproj -scheme Wishlist \
  -configuration Debug -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath ios/DerivedData CODE_SIGNING_ALLOWED=NO build
./gradlew :shared:iosSimulatorArm64Test
```

2026-10-05 Xcode 26.6에서 Debug/Release arm64 simulator build와 Release arm64 device build를 확인했다. iPhone 17 Pro / iOS 26.5 simulator에서 Debug 앱을 설치·실행하고 라이트·다크 화면의 공통 코드 문구를 확인했다. device build는 서명 없이 수행했으며 실제 iPhone 설치·실행과 배포 서명은 검증하지 않았다. shared iOS test는 `:shared:iosSimulatorArm64Test`로 simulator에서 실행한다.

### 공유 확장 확인 빌드(iOS)

`CODE_SIGNING_ALLOWED=NO` 빌드에는 entitlements가 들어가지 않아 app group이 없다(확장이 inbox를 쓸 수 없고 앱은 inbox 가져오기를 끈다). 시뮬레이터에서 공유 확장을 확인할 때는 서명 override 없이 빌드해 프로젝트 기본 "Sign to Run Locally"(개발자 팀 불필요)로 설치한다. `xcodebuild test`가 같은 bundle id의 서명 없는 앱을 다시 설치하므로 테스트 뒤에도 이 빌드를 다시 설치한다.

```sh
# repository root에서
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xcodebuild build -project client/ios/Wishlist.xcodeproj -scheme Wishlist \
  -destination 'platform=iOS Simulator,id=<기기 id>' -derivedDataPath /tmp/wishlist-signed-dd
xcrun simctl install <기기 id> /tmp/wishlist-signed-dd/Build/Products/Debug-iphonesimulator/Wishlist.app
xcrun simctl get_app_container <기기 id> app.wishlist.ios groups   # group.app.wishlist 경로가 나와야 한다
```

Safari 등에서 공유 → "위시리스트"를 고른다(앱 줄에 없으면 "더 보기"에서 켠다). 실기기 설치·배포는 개발자 팀과 app group 등록이 필요하다. 근거는 [ADR-030](../docs/history/architecture/client/ADR-030-share-receipt-mode.md).

## 디자인 토큰

색·모서리·간격·모션 상수는 `python3 client/tools/gen_tokens.py`로 생성한다(`--check`는 생성물이 오래됐는지 검사). 생성물은 손으로 고치지 않는다.

```sh
# repository root에서
python3 -m unittest client/tools/test_gen_tokens.py
python3 client/tools/gen_tokens.py --check
```

색 입력은 `#RRGGBB`만 허용한다. `#RGB`·8자리 hex 등은 생성 단계에서 실패한다.

`.github/workflows/client-checks.yml`은 클라이언트·모션 토큰 변경의 PR과 develop push에서 위 토큰 검사를 실행한다. Android Debug/Release 단위 테스트·빌드와 lint, arm64 iOS simulator 테스트·Release 빌드 job은 PR마다 10분 이상 걸려 병목이 되어 2026-10-07부터 자동 실행을 끄고, Actions의 "Run workflow"(수동 실행)에서만 돈다. 그동안 두 플랫폼 검증은 로컬 명령(아래 "테스트와 데모")으로 한다. iOS CI는 `macos-15` arm64 / Xcode 26.0.1에서 iOS 17.5·26.0.1 matrix를 사용한다(러너의 iOS 26.0 시뮬레이터 런타임 버전 문자열이 `26.0.1`이다). `prepare-ci-simulator.sh`가 필요한 runtime을 다운로드·설치하고 전용 기기를 만든다. 테스트 전에 시뮬레이터의 앱 접근성(`ApplicationAccessibilityEnabled`)을 켠다. SwiftUI는 이 설정이 켜져 있을 때만 접근성 요소를 만들고 `WLTypographyTests`가 그 트리를 읽는다. PR 브랜치 push는 실행하지 않아 PR과 중복되지 않는다. 첫 원격 실행(PR #7)의 실패 원인과 수정은 [C1 리뷰 기록](../docs/history/architecture/client/c1-review-2026-10-06.md#ci-첫-실행-수정-2026-10-07)에 있다. GitHub에서 required check를 지정하는 branch protection 설정은 별도다. 이번 리뷰의 검증 범위와 실기기 확인 목록은 [C1 리뷰 기록](../docs/history/architecture/client/c1-review-2026-10-06.md)에 있다.

## 테스트와 데모

```sh
cd client
export JAVA_HOME="$(/usr/libexec/java_home -v 17)" ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :android:testDebugUnitTest :android:testReleaseUnitTest :android:assembleDebug :android:assembleRelease :android:lintDebug :shared:allTests

cd ..
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild test \
  -project client/ios/Wishlist.xcodeproj -scheme Wishlist \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -derivedDataPath client/ios/DerivedData CODE_SIGNING_ALLOWED=NO
```

- `/usr/libexec/java_home -v 17`이 다른 JDK를 가리키는 환경에서는 JDK 17 경로를 직접 `JAVA_HOME`에 지정하고 `-Porg.gradle.java.installations.paths="$JAVA_HOME"`를 Gradle에 넘긴다.
- Android 단위 테스트는 가격 형식, 시트 끌기 판단, overlay 상태 기계, `WLNavigator`를 다룬다. iOS `WishlistTests`는 같은 사례를 Swift로 확인한다. iOS 17 확인은 iOS 17.5 simulator를 `-destination 'platform=iOS Simulator,id=<기기 id>'`로 지정한다.
- `:shared:allTests`는 Android host(434개)와 iOS simulator(431개) commonTest를 실행한다(2026-10-09 연동 계약 수정 기준, 실패·skip 0). Android 단위 테스트는 debug/release 각 94개다. iOS XCTest는 앞선 C3 검증에서 117개였으며 이번 연동 수정에서는 XCTest를 재실행하지 않았다. 결과는 `shared/build/test-results`의 XML 건수로 확인하고 `NO-SOURCE`/`SKIPPED`/`UP-TO-DATE`를 실행으로 세지 않는다(필요하면 `--rerun-tasks`). 최종 연동 수정 검증은 [연동 정밀검사·수정 기록](../docs/history/architecture/client/client-server-integration-audit-2026-10-09.md#후속-수정-bugfixclient-server-contract)에 있다. 앞선 단계 검증은 [C3 PR #12 리뷰 기록](../docs/history/architecture/client/c3-pr12-review-2026-10-09.md#로컬-검증), [C3 검증 기록](../docs/history/architecture/client/c3-verification-2026-10-07.md#최종-로컬-검증-task-8)과 [C2 검증 기록](../docs/history/architecture/client/c2-final-verification-2026-10-07.md)에 있다.
- 데모·기능: 홈 탭은 실제 홈(로그인 전 / fake 로그인 뒤)이고 오른쪽 위에서 설정을 연다. 카테고리·목적 탭 첫 화면은 debug 데모(시트·확인창·메뉴, 사진·면 화면 이동, 긴 이름·가격 예시)다. release 빌드는 홈·설정·로그인만 실제 화면이고(로그인 버튼은 실제 인증 전이라 동작하지 않는다) 나머지 탭은 이름만 있는 빈 첫 화면이다.
- debug 시연 hook(cold start에서만 적용): Android `adb shell am start -S -n app.wishlist.android/.MainActivity --el wl.fake.delayItem01 5000`(다음 fake 전송 지연) 또는 `--ei wl.fake.pendingCount 100`(로그인 전 대기 줄 N개), iOS `xcrun simctl launch --terminate-running-process <기기 id> app.wishlist.ios -wl.fake.pendingCount 100`. 공유는 Android `adb shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT 'https://example.com/p/1' -n app.wishlist.android/.share.ShareReceiverActivity`로도 보낼 수 있다. 자세한 내용은 [Android](../docs/architecture/client/android.md)·[iOS](../docs/architecture/client/ios.md) 구조 문서.

## 기능 개발 기준

- [클라이언트 구조](../docs/architecture/client/README.md)
- [셋업 설계와 핸드오프 검토](../docs/architecture/client/initial-setup.md)
- [기능 문서](../docs/product/INDEX.md)
- [디자인 결정](../docs/design/decisions.md), [완성 화면](../design/handoff/screens/README.md), [인터랙션](../design/handoff/interactions/README.md)

화면은 핸드오프 [README](../design/handoff/README.md)의 순서(디자인 결정 → 완성 화면 → 와이어프레임)를 기준으로 구현한다. 단계 구성은 [구현 로드맵](../docs/superpowers/specs/2026-10-05-client-implementation-roadmap-design.md)을 따른다.
