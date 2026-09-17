# Server 구조

> 상태: **부분 확정** — MVP 서버는 Kotlin/JVM + Ktor, Neon PostgreSQL과 Firebase Authentication을 사용한다. Hosting과 Queue의 구체적 선택은 후속 설계에서 결정한다.

## 논리 모듈

- **API**: 인증된 요청의 생성·조회·수정, 짧은 응답
- **Application**: wishlist/product/category use case와 transaction 경계
- **Extraction**: URL 검증, fetch, HTML parser, canonicalization
- **Classification**: taxonomy 입력 구성, 외부 LLM 호출, 결과 검증
- **Worker**: queue 소비, retry, 중복 전달에도 안전한 idempotent 상태 갱신
- **Persistence**: PostgreSQL repository와 migration
- **Integration**: Auth, queue, LLM, browser rendering adapter

Ktor의 HTTP routing과 plugin은 adapter 계층에 둔다. domain/application 로직이 Ktor, AWS, DB driver에 직접 의존하지 않게 해 향후 테스트와 기술 교체의 비용을 낮춘다.

## 관리형 PostgreSQL과 Auth

- PostgreSQL 제공자는 Neon을 사용한다.
- Auth는 Firebase Authentication을 사용하고 첫 출시에는 Apple·Google 로그인을 제공한다.
- 모바일 앱은 Firebase ID token을 Ktor API에 전달한다. Ktor는 Firebase Admin Java SDK로 token을 검증하고 Firebase UID를 내부 사용자와 연결한다.
- 모바일 앱은 Neon에 직접 접근하지 않는다. 사용자 데이터 접근 권한과 transaction 경계는 Ktor application layer가 소유한다.
- 초기 주요 사용자는 한국으로 가정하되, Neon과 Ktor를 Singapore에 함께 배치하는 방향을 Hosting 공급자 선택과 함께 검토한다.

선택 배경, 비용 가정과 정확한 후속 논의 지점은 [2026-09-15 기술 설계 체크포인트](../../history/architecture/server/technical-design-checkpoint-2026-09-15.md)를 따른다.

## 비동기 등록 흐름

1. API가 URL, 사용자 ID를 검증한다.
2. canonical candidate로 재사용 가능한 `Product` 캐시를 찾는다.
3. `WishlistItem`을 만들고, 재사용 가능한 캐시가 있으면 그 시점의 metadata를 항목에 복사한다.
4. 추가 추출이 필요하면 대상 항목을 `PROCESSING`으로 만들고 작업을 원자적으로 등록한다.
5. API는 처리 완료를 기다리지 않고 item ID와 상태를 응답한다.
6. Worker는 새 저장 요청의 대상 상품만 추출·분류하고 해당 `WishlistItem`의 독립된 snapshot을 완성한다.
7. 추출 결과는 이후 새 항목 생성에 재사용할 수 있도록 `Product` 캐시에 저장할 수 있지만 기존 `WishlistItem`에는 전파하지 않는다.
8. 성공 시 새 항목 상태를 `READY` 또는 `PARTIAL`로 바꾼다. 실패는 `FAILED_RETRYABLE`과 `FAILED_TERMINAL`로 구분하고 안전한 공개 오류 코드와 내부 진단 정보를 분리한다.

## Product 경계

공용 `Product`는 canonical URL과 추출 metadata를 보관하는 재사용 캐시다. `WishlistItem`은 사용자별 원본 URL, 표시용 상품 정보, 카테고리, 목적과 수동 수정값을 자체 보관하는 독립 snapshot이다. 이 경계는 tracking/affiliate URL이 다른 사용자 데이터에 섞이는 것을 줄인다.

새 항목 생성 시 `Product`의 metadata를 `WishlistItem`에 한 번 복사할 수 있지만 그 뒤에는 어느 방향으로도 동기화하지 않는다. `WishlistItem`은 화면 조회 시 `Product`를 실시간 원본으로 사용하지 않는다. `Product` 정보가 나중에 보완돼도 기존 항목은 바뀌지 않고, 사용자의 항목 수정도 `Product`나 다른 사용자의 항목에 반영되지 않는다.

같은 사용자의 여러 `WishlistItem`이 같은 `Product` 캐시에서 만들어지는 것을 허용한다. 캐시 식별자를 출처나 중복 탐지에 사용할 수는 있지만 항목 데이터 동기화의 근거로 사용하지 않는다. 서버는 활성 중복 후보를 찾아 검토 정보로 제공하지만 자동으로 저장을 거부하거나 항목을 병합하지 않는다. 추출 후 canonical URL이 같다고 밝혀진 경우에도 새 항목과 기존 항목을 함께 반환해 사용자가 어느 항목을 삭제할지 또는 모두 유지할지 결정하게 한다.

아카이브에만 있거나 삭제된 과거 항목은 새 저장을 막지 않는다. 새 저장은 별도 활성 `WishlistItem`을 만들고 기존 아카이브 기록은 변경하지 않는다.

## Archive 경계

아카이브의 대상·스냅샷 내용과 복원·삭제 정책은 [구매 결정 종료](../../product/finish-a-purchase-decision.md)가 소유한다. 서버는 그 아카이브 묶음의 생성·복원·삭제를 하나의 원자적 작업으로 영속화한다.

## 신뢰성 원칙

- DB 기록과 queue 등록의 불일치를 막기 위해 transactional outbox 또는 동등한 전달 보장 방식을 검토한다.
- Worker는 같은 작업이 중복 전달·실행되어도 상태 전이와 `WishlistItem` 결과가 한 번 처리한 경우와 같은 최종 결과가 되도록 idempotent해야 한다.
- retry 횟수, backoff, dead-letter 처리, 추출 성공률을 관측 가능하게 만든다.
- [추출 pipeline](extraction-pipeline.md)은 서버 구현의 보안 경계다.
- WishlistItem의 독립 상태 축, idempotency, anchor window와 경쟁 상황은 [WishlistItem 상태 모델과 API 계약](../wishlist-item-state-api.md)을 따른다.

## 기술 미결정 사항

- Queue와 Hosting의 구체적 공급자 및 작업 전달 보장 방식
- JS-rendered 사이트에 Playwright를 언제·어디까지 적용할지
