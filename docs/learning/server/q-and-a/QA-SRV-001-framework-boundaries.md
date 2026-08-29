# QA-SRV-001: 서버의 중요한 경계와 프레임워크의 관계

> 상태: **학습 Q&A** · 날짜: 2026-08-28

## 질문

중요한 경계가 서버의 큰 틀이고, 가장 위의 `Ktor / Spring / FastAPI / Node` 부분이 프레임워크별로 달라진다는 의미인가?

## 짧은 답변

맞다. HTTP 요청을 받는 최상단 transport/framework 부분은 Ktor, Spring Boot, FastAPI, Node.js 계열마다 용어와 구성 방식이 다르다. 그 아래의 HTTP adapter, UseCase, domain rule, DB/Queue/LLM 같은 외부 adapter의 경계는 framework와 독립적으로 유지하는 것이 이 프로젝트의 서버 큰 틀이다.

## 왜 중요한가

Ktor의 routing·plugin을 제품의 핵심 구조로 오해하지 않게 한다. Ktor는 HTTP 요청을 제품 로직에 연결하는 현재의 framework 선택이고, 위시리스트 저장 규칙·상태 전이·상품 추출 정책은 Ktor에 종속되지 않아야 한다.

## 구조

```text
Ktor / Spring Boot / FastAPI / Node.js
  → HTTP adapter
  → UseCase / application logic
  → domain rule
  → DB / Queue / LLM / URL fetch adapter
```

## 관련 학습

- [Ktor Application과 routing](../ktor/LEARN-KTOR-001-application-routing.md)
- [Ktor와 Spring Boot 비교](../frameworks/ktor-vs-spring-boot.md)
