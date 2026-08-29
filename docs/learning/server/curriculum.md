# Ktor 학습 순서

> 상태: **학습 계획** · 최종 갱신: 2026-08-28

이 순서는 서버를 처음 구축하는 사람이 각 기술이 해결하는 문제를 이해하면서 Wishlist MVP를 만들기 위한 것이다. 모든 항목을 처음부터 완벽하게 익히지 않고, 앞 단계의 작은 결과물을 만든 뒤 다음 단계로 진행한다.

| 단계 | 배울 개념 | 쉬운 설명 | 이 프로젝트의 결과물 |
| --- | --- | --- | --- |
| 1 | HTTP와 REST API | 앱과 서버가 URL, method, JSON, status code로 대화하는 약속 | `POST /wishlist`, `GET /health`의 역할을 설명할 수 있음 |
| 2 | Gradle과 Kotlin/JVM | Kotlin 서버를 build·test·실행하고 외부 library를 관리하는 도구 | 독립 `server` JVM module과 로컬 실행 명령 |
| 3 | Ktor Application·routing | 들어온 HTTP 요청을 어떤 Kotlin 함수가 처리할지 연결하는 구조 | health endpoint와 wishlist API skeleton |
| 4 | serialization·validation·오류 처리 | JSON을 Kotlin 객체로 안전하게 변환하고 잘못된 입력에 일관되게 응답 | request/response DTO, validation, 오류 코드 규칙 |
| 5 | configuration·secret | 개발·운영 환경의 URL, 키, DB 설정을 코드 밖에서 안전하게 주입 | 환경변수 기반 config, secret을 commit하지 않는 규칙 |
| 6 | 테스트와 logging | 기능이 유지되는지 자동 확인하고, 운영 중 문제의 단서를 남기는 방법 | API unit/integration test와 구조화 log |
| 7 | PostgreSQL·migration·transaction | 데이터를 보관하고, schema 변경을 안전하게 적용하며, 여러 DB 변경을 하나의 작업으로 묶는 방법 | Product/WishlistItem schema와 migration |
| 8 | domain/application/adapter 경계 | 제품 규칙을 Ktor·DB·AWS 같은 기술 세부 사항에서 분리하는 방법 | modular monolith package 구조와 UseCase |
| 9 | Auth와 authorization | 사용자가 누구인지 확인하고, 자신의 wishlist만 볼 수 있게 하는 방법 | managed Auth token 검증과 user ownership 검사 |
| 10 | Queue·Worker·idempotency | 느린 작업을 뒤로 보내고, 재시도·중복 실행에도 데이터가 안전하도록 만드는 방법 | Product extraction job과 Worker |
| 11 | 외부 HTTP·SSRF 방어 | 서버가 임의 URL을 요청할 때 내부망 공격을 막는 방법 | 안전한 fetch policy와 extraction pipeline |
| 12 | Docker·AWS 배포 | 서버와 의존성을 image로 포장해 ECR, App Runner/ECS에서 실행하는 방법 | staging 배포와 health check |
| 13 | 관측과 운영 | 장애·비용·성공률을 측정하고 재시도 문제를 파악하는 방법 | metrics, alert 기준, 간단한 runbook |

## 추천 진행 단위

현재 진행: **4단계 — [JSON serialization, validation, 오류 처리](ktor/LEARN-KTOR-002-json-validation-error-handling.md)**

### Foundation

1~6단계까지 진행한다. DB나 실제 Auth 없이도 API가 JSON을 받고 응답하며, 테스트·log·설정을 갖춘 작은 Ktor 서버를 만든다.

### Core MVP

7~10단계로 실제 Product/WishlistItem 저장, 사용자 소유권, 비동기 Worker를 만든다.

### External integration and deployment

11~13단계로 URL extraction 보안, AWS 배포, 관측을 추가한다.

## 매 단계에서 지킬 원칙

- 새 library를 추가하기 전에 “어떤 문제를 해결하는가”와 “대안은 무엇인가”를 학습 문서에 기록한다.
- 코드보다 먼저 상태 전이와 failure case를 설명할 수 있어야 한다.
- 기능이 추가될 때마다 테스트와 관측 방법을 함께 고려한다.
- 모든 cloud 서비스 선택은 필요해지는 단계에서 결정하고, 지금 미리 도입하지 않는다.
