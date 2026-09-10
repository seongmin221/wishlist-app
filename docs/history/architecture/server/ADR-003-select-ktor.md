# ADR-003: MVP 서버 프레임워크로 Ktor를 채택한다

> 상태: **확정** · 날짜: 2026-08-28 · 영역: **server**

## 맥락

MVP 서버 언어는 Kotlin/JVM으로 결정했다. 서버는 작은 팀이 운영하는 modular monolith이며, mobile API와 Product Worker를 같은 코드베이스의 논리적 역할로 둔다. 상품 URL fetch와 외부 LLM 호출은 I/O 대기 시간이 큰 작업이므로 비동기 처리가 중요하다.

Ktor와 Spring Boot를 비교한 결과, 서버 경험이 많지 않은 상황에서 필요한 서버 구성 요소를 하나씩 이해하고 작은 MVP에 맞는 구조를 만들 수 있는 선택이 필요했다.

## 결정

MVP 서버 프레임워크로 **Ktor**를 채택한다.

Spring Boot는 채택하지 않지만, 향후 재평가 가능한 강한 대안으로 유지한다. 지금부터 Spring Boot migration을 목표로 별도 구현을 하지는 않는다.

## 이유와 trade-off

- Android/KMP에서 이미 쓰는 Kotlin, Gradle, coroutine 사고방식을 활용할 수 있다.
- 필요한 HTTP routing, JSON serialization, 인증, 오류 처리, 관측 기능을 단계적으로 도입하며 서버의 책임을 학습할 수 있다.
- native mobile API와 Product Worker 중심의 MVP에 필요한 만큼만 구성할 수 있다.
- URL fetch, DB, Queue, LLM API 호출처럼 I/O 대기가 많은 흐름과 Kotlin coroutine이 잘 맞는다.

대신 Ktor는 Spring Boot보다 기본 관례와 자동 구성이 적다. DB transaction, 인증, health check, metrics, dependency injection의 선택과 구성 기준을 프로젝트가 명확히 정해야 한다.

## Spring Boot 재검토 조건

- 인증·권한·감사·운영 지표 요구가 빠르게 복잡해진다.
- 복잡한 DB transaction, 다수의 외부 연동, back-office API가 빠르게 증가한다.
- 서버 개발자와 협업하거나 채용하면서 Spring의 생태계·관례가 더 큰 이점이 된다.
- Ktor의 최소 운영 검증에서 설정·테스트·관측 유지보수 비용이 과도하다고 확인된다.

## migration 비용을 낮추는 원칙

Ktor의 routing·plugin·DB driver를 domain/application 로직에 직접 섞지 않는다. HTTP handler, persistence, queue, LLM 등은 adapter 경계에 두고 UseCase는 framework 독립적으로 유지한다. 이 원칙은 Spring Boot로의 전환뿐 아니라 현재 Ktor 코드의 테스트와 유지보수에도 도움이 된다.
