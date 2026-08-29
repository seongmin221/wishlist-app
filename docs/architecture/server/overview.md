# Server 구조

> 상태: **부분 확정** — MVP 서버는 Kotlin/JVM + Ktor를 사용한다. 클라우드 공급자와 DB/Auth/Queue의 구체적 선택은 후속 설계에서 결정한다.

## 논리 모듈

- **API**: 인증된 요청의 생성·조회·수정, 짧은 응답
- **Application**: wishlist/product/category use case와 transaction 경계
- **Extraction**: URL 검증, fetch, HTML parser, canonicalization
- **Classification**: taxonomy 입력 구성, 외부 LLM 호출, 결과 검증
- **Worker**: queue 소비, retry, idempotent한 상태 갱신
- **Persistence**: PostgreSQL repository와 migration
- **Integration**: Auth, queue, LLM, browser rendering adapter

Ktor의 HTTP routing과 plugin은 adapter 계층에 둔다. domain/application 로직이 Ktor, AWS, DB driver에 직접 의존하지 않게 해 향후 테스트와 기술 교체의 비용을 낮춘다.

## 비동기 등록 흐름

1. API가 URL, 사용자 ID를 검증한다.
2. canonical candidate로 기존 Product를 찾는다.
3. `WishlistItem`을 `PROCESSING`으로 만들고 작업을 원자적으로 등록한다.
4. API는 처리 완료를 기다리지 않고 item ID와 상태를 응답한다.
5. Worker가 추출과 분류를 수행한다.
6. 성공 시 Product metadata를 갱신하고 item 상태를 `READY` 또는 `PARTIAL`로 바꾼다.
7. 재시도 불가능한 실패는 `FAILED`와 사용자에게 보여줄 안전한 오류 사유로 남긴다.

## Product 경계

공용 `Product`에는 canonical URL과 공용 metadata를 둔다. 사용자별 공유 URL과 수동 category override는 `WishlistItem`에 둔다. 이 경계는 tracking/affiliate URL이 다른 사용자 데이터에 섞이는 것을 줄인다.

## 신뢰성 원칙

- DB 기록과 queue 등록의 불일치를 막기 위해 transactional outbox 또는 동등한 전달 보장 방식을 검토한다.
- Worker 작업은 중복 실행되어도 같은 최종 결과가 나와야 한다.
- retry 횟수, backoff, dead-letter 처리, 추출 성공률을 관측 가능하게 만든다.
- [추출 pipeline](extraction-pipeline.md)은 서버 구현의 보안 경계다.
