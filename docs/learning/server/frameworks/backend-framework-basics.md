# 백엔드 프레임워크란 무엇인가?

> 상태: **학습 노트** · 기술 선택 상태: **Kotlin/JVM + Ktor 채택** · 최종 갱신: 2026-08-28

## 서버가 하는 일

모바일 앱은 화면을 보여주고 사용자의 입력을 받는다. 서버는 앱의 요청을 받아 인증하고, 데이터베이스에 데이터를 저장·조회하고, 오래 걸리는 작업을 배경에서 처리한다.

이 프로젝트의 한 요청은 다음과 같이 흐른다.

```text
앱이 URL 공유
  → API 서버가 WishlistItem을 PROCESSING으로 저장
  → 앱에는 즉시 성공 응답
  → 별도 Worker가 URL fetch, metadata 추출, AI 분류
  → DB 상태를 READY/PARTIAL/FAILED로 갱신
```

백엔드 프레임워크는 이 흐름에서 HTTP 요청을 받고 JSON을 읽고 쓰며, URL별 처리 함수를 연결하고, 오류 처리·로그·설정·테스트의 기본 골격을 제공한다. 프레임워크 자체가 DB, Auth, Queue, LLM을 대신 제공하는 것은 아니다. 이들은 프레임워크에 연결할 외부 구성 요소다.

## 왜 비동기가 중요한가

상품 페이지 fetch, redirect, JavaScript rendering, 외부 LLM 호출은 수백 ms에서 수 초까지 걸리거나 실패할 수 있다. API 요청에서 모두 기다리면 앱의 “저장” 경험이 느려진다. 따라서 API는 빠르게 DB에 기록하고, Worker가 뒤에서 느린 I/O 작업을 처리한다.

여기서 비동기(asynchronous)는 CPU가 더 빨라진다는 뜻이 아니다. 네트워크·DB·외부 API 응답을 기다리는 동안 다른 요청을 처리할 수 있도록 만드는 방식이다. 이 프로젝트의 주요 지연은 이런 I/O 작업이므로 중요하다.

## 후보 비교

| 후보 | 무엇인가 | 장점 | 단점·학습 비용 | 이 프로젝트 적합성 |
| --- | --- | --- | --- | --- |
| **Ktor** | Kotlin 기반의 가벼운 서버 프레임워크 | Android/KMP와 같은 Kotlin 언어, coroutine 기반 I/O, 작은 API·Worker를 명시적으로 구성하기 좋음 | Spring보다 기본 선택을 직접 해야 하며, 서버 생태계 학습이 필요 | **우선 검토**: Kotlin 경험을 활용하면서 modular monolith MVP에 맞음 |
| **Spring Boot** | Kotlin 또는 Java로 쓰는 종합 서버 프레임워크 | DB, 보안, 설정, health check, metrics 등 production 기능과 학습 자료가 매우 풍부 | 규칙·개념·설정이 많고, 작은 MVP에는 구조가 무거울 수 있음 | 장기적으로 복잡한 백오피스·팀 확장을 예상하면 강한 대안 |
| **Node.js + TypeScript** | JavaScript/TypeScript runtime와 웹 프레임워크 조합 | HTTP·JSON 생태계가 크고 비동기 I/O에 자연스러움 | Kotlin 모델을 공유할 수 없고, 언어·도구 체계를 하나 더 운영 | 팀에 TypeScript 경험이 많거나 웹 관리 화면까지 같은 언어로 만들 때 유리 |
| **Python + FastAPI** | Python type hint 기반 API 프레임워크 | API 작성과 AI/데이터 실험을 빠르게 시작하기 좋고 자동 OpenAPI 문서 제공 | Python 런타임·패키지·비동기와 blocking library 혼용을 별도로 익혀야 하며 Kotlin 공유 없음 | 자체 ML/데이터 파이프라인이 핵심일 때 유리하지만 MVP의 AI는 외부 API 호출이므로 결정적 이점은 작음 |

Spring Boot와 Ktor는 모두 Kotlin으로 작성할 수 있다. 따라서 “Kotlin을 쓰는가”와 “Ktor 또는 Spring을 쓰는가”는 별개의 질문이다.

## 이 프로젝트에서의 1차 판단 기준

1. **MVP가 작고 solo 운영인가?**: Ktor의 단순한 구성에 가점.
2. **서버의 관례와 안전장치를 많이 제공받고 싶은가?**: Spring Boot에 가점.
3. **Android/KMP 경험을 최대한 활용하고 싶은가?**: Kotlin 계열에 가점. 단, client와 server 코드를 억지로 공유할 필요는 없다.
4. **AI/데이터 처리가 서버의 중심인가?**: Python에 가점. 현재는 외부 LLM API 호출이므로 해당하지 않는다.
5. **웹 프론트엔드까지 TypeScript로 통일할 계획인가?**: Node.js에 가점. 현재 native mobile 중심이므로 해당하지 않는다.

Cloud Run처럼 컨테이너를 실행하는 hosting은 이 네 후보 모두 수용할 수 있다. 따라서 hosting은 이 프레임워크 선택의 결정적인 제약이 아니다.

## 선택 결과

MVP 서버 프레임워크로 **Kotlin + Ktor**를 채택했다. Spring Boot는 가장 강한 재검토 대안으로 남긴다.

- Ktor는 Android/KMP에서 이미 쓰는 Kotlin과 coroutine 사고방식을 활용할 수 있다.
- 상품 추출 Worker는 주로 HTTP fetch와 LLM 호출을 기다리는 I/O workload라 coroutine 기반 서버와 잘 맞는다.
- 이 선택이 KMP와 server 구현을 한 모듈로 섞는다는 뜻은 아니다. server는 Kotlin/JVM의 독립 모듈이고, API contract 또는 순수 model 일부만 신중하게 공유한다.
- Spring Boot는 MVP에 과한 선택일 수 있지만, 보안·운영·DB 통합의 폭넓은 관례를 우선시하거나 서버 개발자가 합류할 가능성이 크면 더 안전한 선택이 될 수 있다.

최종 선택 전에는 각 후보로 “URL 저장 API + background Worker의 최소 skeleton”을 같은 요구사항으로 비교하고, 테스트·DB migration·Cloud Run 배포 경험을 평가한다.

## 공식 자료

- [Ktor: REST API 만들기](https://ktor.io/docs/server-create-restful-apis.html)
- [Spring Boot: 웹 애플리케이션](https://docs.spring.io/spring-boot/reference/web/index.html)
- [Node.js: event-driven runtime 소개](https://nodejs.org/en/about)
- [FastAPI: type hint 기반 API 프레임워크](https://fastapi.tiangolo.com/)
- [Python asyncio: I/O-bound 비동기 코드](https://docs.python.org/3/library/asyncio.html)
