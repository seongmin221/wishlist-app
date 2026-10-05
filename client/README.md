# Wishlist client

iOS는 SwiftUI, Android는 Jetpack Compose, 공통 비즈니스 계층은 KMP로 구성한다. 서버는 독립적인 `server/` build이며 이 build에 포함하지 않는다.

## 현재 범위

초기 앱 진입점과 공통 코드 연결만 구성했다. 두 앱은 `shared`의 `AppInfo.displayName`을 표시한다. 로그인, 상품 저장, 공유 수신, DB, API, 탭 화면과 디자인 모션은 아직 구현하지 않았다.

| 위치 | 역할 |
| --- | --- |
| `android/` | Compose application, AGP 내장 Kotlin |
| `shared/` | Android·iOS Kotlin library, UI 의존성 없음 |
| `ios/` | SwiftUI app, Xcode project와 공유 scheme |
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

`shared`는 Android host test와 `commonTest`의 Kotlin test dependency를 준비했다. 아직 비즈니스 기능·테스트가 없으므로 test task는 `NO-SOURCE`다. 테스트 통과와 구분한다. 첫 공통 기능부터 `shared/src/commonTest/kotlin/`에 테스트를 추가한다.

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

2026-10-05 Xcode 26.6에서 Debug/Release arm64 simulator build와 Release arm64 device build를 확인했다. iPhone 17 Pro / iOS 26.5 simulator에서 Debug 앱을 설치·실행하고 라이트·다크 화면의 공통 코드 문구를 확인했다. device build는 서명 없이 수행했으며 실제 iPhone 설치·실행과 배포 서명은 검증하지 않았다. shared iOS test는 아직 테스트가 없어 compile/link가 `NO-SOURCE`, 실행 task가 `SKIPPED`다.

## 디자인 토큰

색·모서리·간격·모션 상수는 `python3 client/tools/gen_tokens.py`로 생성한다(`--check`는 생성물이 오래됐는지 검사). 생성물은 손으로 고치지 않는다.

## 기능 개발 기준

- [클라이언트 구조](../docs/architecture/client/README.md)
- [셋업 설계와 핸드오프 검토](../docs/architecture/client/initial-setup.md)
- [기능 문서](../docs/product/INDEX.md)
- [디자인 결정](../docs/design/decisions.md), [완성 화면](../design/handoff/screens/README.md), [인터랙션](../design/handoff/interactions/README.md)

화면은 핸드오프 [README](../design/handoff/README.md)의 순서(디자인 결정 → 완성 화면 → 와이어프레임)를 기준으로 구현한다. 단계 구성은 [구현 로드맵](../docs/superpowers/specs/2026-10-05-client-implementation-roadmap-design.md)을 따른다.
