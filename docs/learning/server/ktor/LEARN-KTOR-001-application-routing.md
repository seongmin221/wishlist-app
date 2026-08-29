# Ktor Application과 routing

> 상태: **학습 노트** · 단계: **Ktor 학습 3단계** · 최종 갱신: 2026-08-28

## Ktor가 요청을 처리하는 전체 흐름

Ktor는 HTTP 서버를 만들기 위한 framework다. AWS container에서 server process가 실행되면, network engine이 port를 열고 HTTP 요청을 받는다. Ktor Application은 그 요청을 처리할 규칙을 등록한 공간이다.

```text
HTTP request
  → Ktor engine
  → Application plugins
  → routing
  → route handler
  → UseCase
  → DB / Queue / external API adapter
  → HTTP response
```

이 흐름에서 route handler는 “HTTP 세계”와 “제품 로직”의 경계다. handler가 직접 SQL을 실행하거나 LLM을 호출하지 않고 UseCase에 일을 맡긴다.

## Application이란 무엇인가

Ktor Application은 server가 시작할 때 한 번 구성된다. 이때 다음을 등록한다.

- 어떤 plugin을 사용할지
- 어떤 HTTP path와 method를 지원할지
- request를 어느 handler로 보낼지
- 공통 오류 처리와 logging을 어떻게 할지

Application을 만드는 것은 앱 시작 시 dependency graph와 navigation을 구성하는 것과 비슷하지만, 화면 대신 HTTP 요청의 흐름을 구성한다.

## routing이란 무엇인가

routing은 HTTP method와 path를 handler에 연결하는 표다.

```text
GET    /health                 → service health 확인
POST   /wishlist               → 위시리스트 항목 생성
GET    /wishlist               → 내 위시리스트 목록 조회
PATCH  /wishlist/{itemId}      → category 등 일부 변경
DELETE /wishlist/{itemId}      → 항목 삭제
```

Ktor는 요청의 method와 path가 일치하는 handler를 찾아 실행한다. path parameter인 `{itemId}`는 handler가 읽을 수 있는 입력값이다.

## route handler의 책임

좋은 handler는 얇다.

```text
1. 인증된 사용자 정보를 얻는다.
2. JSON request를 DTO로 읽는다.
3. 입력값을 검증한다.
4. UseCase를 호출한다.
5. 결과를 response DTO와 status code로 변환한다.
```

아래 책임은 handler에 넣지 않는다.

- SQL 작성과 DB connection 관리
- Queue의 재시도 정책
- URL fetch와 SSRF 검증
- HTML parsing과 LLM 호출
- 제품 상태 전이의 복잡한 규칙

이런 로직은 application/domain/adapter 계층으로 분리한다. 그러면 HTTP 없이 unit test할 수 있고, 나중에 Ktor에서 Spring Boot로 바꾸더라도 핵심 로직의 교체 비용이 줄어든다.

## Ktor plugin이란 무엇인가

plugin은 모든 요청 또는 응답에 공통으로 적용하는 기능이다. Android의 interceptor, middleware와 비슷하게 생각할 수 있다.

| Plugin 범주 | 해결하는 문제 | 이 프로젝트에서의 역할 |
| --- | --- | --- |
| Content negotiation | JSON과 Kotlin 객체 변환 | request DTO를 읽고 response DTO를 JSON으로 반환 |
| Authentication | 요청 사용자가 누구인지 확인 | access token 검증 후 user ID 제공 |
| Status pages | 예외를 안정적인 HTTP 오류로 변환 | 내부 예외 대신 `INVALID_URL` 같은 오류 body 반환 |
| Call logging | 요청·응답·실패의 log 남김 | API 오류와 latency 조사 |
| CORS | browser가 다른 domain API를 호출할 수 있는지 제어 | native mobile MVP에는 불필요, 공유 웹페이지 도입 시 검토 |

plugin은 route마다 반복하면 안 되는 공통 책임을 한 곳에 모은다. 다만 plugin에 제품 비즈니스 로직을 과도하게 넣으면 흐름을 추적하기 어려워진다.

## health endpoint는 왜 필요한가

`GET /health`는 일반 사용자 기능이 아니다. AWS가 container가 살아 있고 요청을 받을 수 있는지 확인하기 위한 운영 endpoint다.

초기에는 “Ktor application이 실행 중인가”만 확인해도 된다. DB, Queue, LLM까지 모두 확인할지는 각 dependency가 장애일 때 health check가 어떤 행동을 해야 하는지 결정한 뒤 추가한다.

## API versioning

향후 mobile 앱의 여러 버전이 동시에 존재할 수 있으므로 `/v1/wishlist`처럼 URL에 major API version을 넣는 방식을 검토한다. 하지만 지금은 API contract 자체가 초안이므로 정확한 versioning 방식은 API specification 단계에서 결정한다.

## API와 Worker의 관계

Ktor routing은 모바일의 HTTP API를 담당한다. Product Worker는 queue에서 작업을 받아 처리하므로 일반적으로 HTTP route가 필요하지 않다.

MVP에서는 API와 Worker를 같은 `server` codebase에 둔다. 공통 UseCase와 adapter를 공유하되, 나중에 AWS 운영 방식에 따라 API container와 Worker process를 별도로 실행할 수 있다.

## 다음 단계

다음에는 JSON serialization, validation, 오류 처리를 배운다. routing이 요청을 handler까지 보내는 방법이라면, 다음 단계는 handler가 잘못된 입력을 안전하고 일관되게 처리하는 방법이다.
