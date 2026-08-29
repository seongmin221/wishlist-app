# ADR-002: MVP 서버 구현 언어는 Kotlin을 우선한다

> 상태: **확정** · 날짜: 2026-08-27 · 영역: **server**

## 맥락

이 프로젝트의 mobile client와 KMP shared 계층은 Kotlin을 사용한다. MVP는 native mobile 중심이고 AI는 자체 모델 학습이 아니라 외부 LLM API 호출을 사용한다. 추후 위시리스트 공유 웹페이지를 고려하지만 MVP 범위에는 포함하지 않는다.

## 결정

MVP 서버 구현 언어는 Kotlin/JVM을 우선한다. 서버 프레임워크는 Ktor와 Spring Boot를 비교한 뒤 별도로 결정한다.

## 이유와 trade-off

- Android/KMP에서 익숙한 Kotlin, Gradle, coroutine 개념을 활용해 서버 학습 부담을 줄인다.
- 이 프로젝트의 Worker는 HTTP fetch, DB, 외부 LLM API처럼 I/O 대기 작업이 많다.
- Python의 AI 생태계 이점은 외부 LLM API를 호출하는 MVP에서는 결정적이지 않다.
- Node.js/TypeScript는 향후 웹페이지 구현 시 검토할 수 있지만, 아직 mobile 외의 web client는 MVP 범위가 아니다.

Kotlin을 고른다고 client와 server 코드를 무리하게 공유하지는 않는다. server는 독립 Kotlin/JVM 모듈로 유지한다.

## 재검토 조건

- 자체 ML 모델 학습·데이터 pipeline이 MVP 이후 핵심 기능이 된다.
- 공유 웹페이지 또는 운영 웹 콘솔이 제품의 큰 비중을 차지하고 TypeScript 통일의 이점이 커진다.
- Ktor/Spring Boot의 최소 검증에서 Kotlin 서버 운영성이 기대보다 낮다.
