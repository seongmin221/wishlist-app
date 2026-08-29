# KMP 학습

Kotlin Multiplatform은 UI 통합 도구가 아니라, iOS와 Android가 공통으로 쓰는 domain/data/sync 규칙을 공유하기 위한 기반이다. network engine, local DB driver, secure storage 같은 platform 구현은 `expect/actual` 또는 DI 경계를 통해 분리한다.
