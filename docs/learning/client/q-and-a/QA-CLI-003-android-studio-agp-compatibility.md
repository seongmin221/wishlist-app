# QA-CLI-003: CLI 빌드는 성공했는데 Android Studio에서 AGP 호환 오류가 나는 이유

> 2026-10-05 · 초기 클라이언트 셋업 후 Android Studio sync 오류

## 질문

Android APK 빌드는 성공했는데 Studio에서 `AGP 9.0.0`이 호환되지 않고 `Latest supported version is AGP 8.11.0`이라고 나오는 이유는 무엇인가?

## 답변

Gradle wrapper는 지정된 Gradle·plugin으로 CLI 빌드를 실행한다. Android Studio는 별도로 지원하는 AGP 범위가 있으며, 범위를 넘는 프로젝트는 IDE sync 단계에서 거부한다. 따라서 CLI 빌드 성공과 IDE 호환성은 각각 확인해야 한다.

이 기기의 Studio 설치 정보는 Narwhal 2025.1.1이다. 프로젝트 AGP는 9.0.0이지만 해당 Studio의 지원 상한은 8.11이다. AGP 9.0 지원은 Otter 3 Feature Drop 2025.2.3부터다. 현재 구성은 지원하는 Studio로 업데이트한 뒤 `client/`를 다시 열고 sync하는 방법으로 사용할 수 있다.

기존 Studio를 유지하려면 AGP를 낮추면서 Gradle wrapper, Kotlin Android plugin 적용, KMP Android DSL까지 함께 검토해야 한다. AGP 9의 Android 앱은 내장 Kotlin을 사용하므로 버전 문자열만 8.11로 바꾸는 방식으로 해결하지 않는다.

## 근거

- [Android Studio/AGP 호환 표와 업데이트 방법](https://developer.android.com/studio/releases#android_gradle_plugin_and_android_studio_compatibility)
- [AGP 9.0의 내장 Kotlin](https://developer.android.com/build/releases/agp-9-0-0-release-notes)
- [KMP 호환 표](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)

## 해결 확인

2026-10-05 사용자가 Studio를 2026.1.3으로 업데이트했다. 설치 정보의 버전과 IDE 로그의 `onSuccess(RESOLVE_PROJECT)`·`onImportFinished`·`Gradle sync finished`를 확인했다. 프로젝트 AGP 9.0.0은 그대로 유지했고, APK·lint와 API 36 emulator의 설치·실행·라이트/다크 표시도 검증했다.
