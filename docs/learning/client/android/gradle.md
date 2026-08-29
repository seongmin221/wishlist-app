# Gradle

> 상태: **검토 예정**

Gradle은 Android와 Kotlin 프로젝트의 build, test, dependency 관리를 수행하는 빌드 도구다. KMP에서는 Android·iOS 등 여러 target의 source set과 의존성을 선언하는 중심 역할도 한다.

이 프로젝트에서 중요한 학습 주제는 version catalog, build logic 분리, KMP source set 의존성 경계다. 처음부터 복잡한 convention plugin 구조를 만들기보다 모듈 수가 늘어날 때 단계적으로 도입한다.
