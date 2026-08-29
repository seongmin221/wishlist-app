# Kotlin/JVM과 Gradle: Ktor 서버 프로젝트의 기반

> 상태: **학습 노트** · 단계: **Ktor 학습 2단계** · 최종 갱신: 2026-08-28

## Kotlin/JVM이란 무엇인가

Kotlin/JVM은 Kotlin 코드를 JVM(Java Virtual Machine) 위에서 실행하는 방식이다. Kotlin compiler가 코드를 JVM이 이해할 수 있는 형태로 만들고, JDK가 그 결과를 실행한다.

Android도 Kotlin과 JVM 계열 도구를 사용하지만 최종 결과물은 Android 기기에서 실행되는 APK/AAB다. Ktor 서버의 최종 결과물은 Linux container 안에서 계속 실행되는 JVM application이다.

```text
Android
  Kotlin source → Android build → APK/AAB → 사용자 기기

Ktor server
  Kotlin source → JVM build → JAR/application → Docker image → AWS container
```

## 서버 프로그램은 어떻게 계속 실행되는가

모바일 앱은 사용자가 실행하고 종료할 수 있다. 서버는 AWS에서 실행된 뒤, 종료되지 않는 한 계속 네트워크 요청을 기다린다.

```text
서버 시작
  → 설정 읽기
  → Ktor와 필요한 구성 초기화
  → 지정된 port에서 HTTP 요청 대기
  → 요청마다 적절한 handler 실행
```

`GET /health` 또는 `POST /wishlist` 요청이 오면 Ktor가 해당 요청을 Kotlin 코드로 전달한다. server의 `main` entry point는 이 프로그램을 시작하는 출발점이다.

## Gradle의 역할

Gradle은 Android에서처럼 다음 일을 한다.

- Kotlin source를 compile한다.
- 외부 library를 내려받고 version을 관리한다.
- test를 실행한다.
- 실행 가능한 JVM application 또는 JAR를 만든다.
- 이후 Docker image build와 CI 작업을 연결할 수 있다.

Android와 다른 점은 Android Gradle Plugin, emulator, manifest, resource, APK/AAB, build variant가 기본 관심사가 아니라는 것이다. 서버에서는 Kotlin/JVM plugin과 application packaging이 중심이다.

| Android Gradle 개념 | Ktor server에서 대응되는 개념 |
| --- | --- |
| Android application module | Kotlin/JVM server module |
| APK/AAB | JAR 또는 실행 가능한 application distribution |
| emulator/device 실행 | local JVM process 실행 |
| Build variant/flavor | 주로 환경변수 또는 config로 환경 구분 |
| Android SDK dependency | JVM/Ktor/DB/HTTP library dependency |

## 예상 프로젝트 구조

아직 코드 구조를 확정하거나 생성하지는 않는다. Ktor를 시작할 때의 최소 구조는 대략 아래와 같다.

```text
server/
  build.gradle.kts       # server module의 build recipe
  src/main/kotlin/       # 실제 server 코드
  src/test/kotlin/       # server test
  src/main/resources/    # 기본 설정 파일이 필요할 경우
```

나중에 mobile과 server를 같은 repository에 둘 때도 server는 독립 Kotlin/JVM module로 둔다. KMP shared module과 server의 책임을 섞지 않는다.

## build.gradle.kts는 무엇인가

`build.gradle.kts`는 Gradle에 “이 module을 어떻게 build할지” 알려 주는 Kotlin DSL 설정 파일이다.

여기에는 보통 다음이 들어간다.

- Kotlin/JVM, Ktor 같은 plugin
- JDK/JVM target
- Ktor, JSON serialization, test library 같은 dependency
- test와 packaging task 설정

처음 Ktor server를 만들 때 필요한 dependency도 한꺼번에 많이 넣지 않는다. HTTP server, JSON, 오류 처리, 테스트처럼 현재 단계에서 필요한 것만 추가한다.

## Gradle Wrapper가 중요한 이유

`gradlew`와 `gradle/wrapper/`는 프로젝트가 사용할 Gradle 버전을 repository에 고정한다. 개발자의 로컬 Gradle 설치 버전이 달라도 같은 build 도구 버전으로 실행하게 해 준다.

서버에서도 Android와 마찬가지로 wrapper를 사용한다. CI와 AWS 배포 image build도 같은 wrapper를 기준으로 수행한다.

## build 결과가 AWS 배포로 가는 과정

```text
Kotlin source
  → Gradle compile
  → test
  → JAR/application package
  → Docker image
  → ECR push
  → App Runner 또는 ECS/Fargate 실행
```

JAR은 JVM에서 실행할 수 있도록 코드와 필요한 library를 묶은 패키지다. Docker image는 JDK/JRE, JAR, 실행 설정 등을 함께 포장한 실행 단위다. AWS는 보통 이 Docker image를 실행한다.

## 지금 결정하지 않는 것

- 정확한 JDK 버전
- database library와 migration tool
- dependency injection library
- Dockerfile과 AWS deployment 방식
- root Gradle multi-module 구성

이들은 실제로 필요한 단계에서, 해결하려는 문제와 대안을 비교한 뒤 결정한다.

## 이번 단계에서 기억할 것

- Ktor는 Kotlin/JVM application으로 실행된다.
- server는 APK가 아니라 AWS에서 계속 요청을 기다리는 process다.
- Gradle은 Android와 같은 build 도구지만, server에서는 JVM build·test·package가 중심이다.
- server module은 KMP shared module과 분리한다.
- Docker와 AWS는 compile 다음 단계이며, 지금은 local JVM 실행부터 이해하면 된다.

## 다음 단계

다음에는 Ktor Application과 routing을 배운다. HTTP 요청이 Ktor에 도착한 뒤 어떤 순서로 handler에 연결되는지, plugin은 무엇인지 설명한다.
