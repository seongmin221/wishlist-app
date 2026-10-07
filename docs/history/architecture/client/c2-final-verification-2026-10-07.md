# C2 최종 로컬 검증 (Task 10)

> 2026-10-07 · 브랜치 `client/c2-kmp-core` HEAD `32ab9fd` 기준 · 서버 계약 기준 `1c6d949`

## 환경

macOS(Apple Silicon), JDK 17(openjdk@17), Xcode 26.6 / iPhone 17 Pro iOS 26.5 simulator. 시뮬레이터 `ApplicationAccessibilityEnabled`를 켠 뒤 실행했다. Gradle은 `--rerun-tasks`로 결과를 새로 만들었다.

## 의존성 대조

`client/gradle/libs.versions.toml`과 Gradle wrapper는 Task 1(`bcb53c9`) 이후 변경이 없다(`git diff`). 값은 [호환성 기록](c2-dependency-compatibility-2026-10-07.md)의 선택 버전과 같다(Kotlin 2.3.21, AGP 9.0.0, Gradle 9.3.0, SKIE 0.10.12, Ktor 3.4.3, serialization 1.10.0, SQLDelight 2.4.1, Koin 4.2.2, coroutines 1.10.2, Turbine 1.2.1, OkHttp 5.3.2). 새 호환성 조사는 하지 않았다.

## 실행 결과

| 검증 | 결과 |
| --- | --- |
| 토큰 생성기 `unittest` / `gen_tokens.py --check` | 8개 통과 / 성공 |
| `:shared:testAndroidHostTest` | 246개, 실패·오류·skip 0 |
| `:shared:iosSimulatorArm64Test` | 243개, 실패·오류·skip 0 (Android host 전용 `OkHttpRedirectTest` 3개 제외) |
| `:android:testDebugUnitTest` / `testReleaseUnitTest` | 각 66개, 실패·오류·skip 0 |
| `:android:assembleDebug` `assembleRelease` `lintDebug` | 성공 |
| `:shared:linkReleaseFrameworkIosArm64` | 성공 |
| `xcodebuild test`(Wishlist scheme, simulator) | XCTest 81개, 실패 0 |
| `xcodebuild` Release simulator build | 성공 |

테스트 건수는 `build/test-results` XML에서 집계했다. `NO-SOURCE`는 테스트 리소스 task에만 나타나며 테스트 task는 모두 실행됐다. 플랫폼 CI 자동 실행 비활성화는 유지했다.

## 문서 정합성

- 제품 API 37개는 `ApiId.kt`와 [연동 상태](../../../architecture/client/server-integration-status.md) 표가 중복·누락 없이 일치한다. 서버 상태는 B1·B2 완료(ITEM-01·03, CAT-01~04)로 저장소 API 목록과 같다.
- Fake 규칙 테스트, MockEngine Remote 검증, 실서버 검증은 표에서 별도 열이다. 실서버는 미실행이다.

## 미실행·인계

- 기존 가격 fixture의 라이트·다크 데모 시각 확인은 수동이며 이번에 실행하지 않았다. 자동 테스트(`PriceFormatTests`, shared `PriceFormatterTest`)는 형식·null/blank 규칙만 확인하고 색 모드는 다루지 않는다.
- Android Context SQLite driver 기기 runtime smoke: C3. Darwin redirect 실제 검증: C12(C2는 Ktor 3.4.3 delegate 소스 검토). 실서버 호출: 미실행.
- 실기기 한글 IME·VoiceOver·API 26 실행은 C3 확인 목록([c3-performance-checks.md](../../../architecture/client/c3-performance-checks.md))에 있다.
