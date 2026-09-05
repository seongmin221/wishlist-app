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
2. canonical candidate로 재사용 가능한 `Product` 캐시를 찾는다.
3. `WishlistItem`을 만들고, 재사용 가능한 캐시가 있으면 그 시점의 metadata를 항목에 복사한다.
4. 추가 추출이 필요하면 대상 항목을 `PROCESSING`으로 만들고 작업을 원자적으로 등록한다.
5. API는 처리 완료를 기다리지 않고 item ID와 상태를 응답한다.
6. Worker는 새 저장 요청의 대상 상품만 추출·분류하고 해당 `WishlistItem`의 독립된 snapshot을 완성한다.
7. 추출 결과는 이후 새 항목 생성에 재사용할 수 있도록 `Product` 캐시에 저장할 수 있지만 기존 `WishlistItem`에는 전파하지 않는다.
8. 성공 시 새 항목 상태를 `READY` 또는 `PARTIAL`로 바꾸고, 재시도 불가능한 실패는 `FAILED`와 안전한 오류 사유로 남긴다.

## Product 경계

공용 `Product`는 canonical URL과 추출 metadata를 보관하는 재사용 캐시다. `WishlistItem`은 사용자별 원본 URL, 표시용 상품 정보, 카테고리, 목적과 수동 수정값을 자체 보관하는 독립 snapshot이다. 이 경계는 tracking/affiliate URL이 다른 사용자 데이터에 섞이는 것을 줄인다.

새 항목 생성 시 `Product`의 metadata를 `WishlistItem`에 한 번 복사할 수 있지만 그 뒤에는 어느 방향으로도 동기화하지 않는다. `WishlistItem`은 화면 조회 시 `Product`를 실시간 원본으로 사용하지 않는다. `Product` 정보가 나중에 보완돼도 기존 항목은 바뀌지 않고, 사용자의 항목 수정도 `Product`나 다른 사용자의 항목에 반영되지 않는다.

같은 사용자의 여러 `WishlistItem`이 같은 `Product` 캐시에서 만들어지는 것을 허용한다. 캐시 식별자를 출처나 중복 탐지에 사용할 수는 있지만 항목 데이터 동기화의 근거로 사용하지 않는다. 서버는 활성 중복 후보를 찾아 검토 정보로 제공하지만 자동으로 저장을 거부하거나 항목을 병합하지 않는다. 추출 후 canonical URL이 같다고 밝혀진 경우에도 새 항목과 기존 항목을 함께 반환해 사용자가 어느 항목을 삭제할지 또는 모두 유지할지 결정하게 한다.

아카이브에만 있거나 삭제된 과거 항목은 새 저장을 막지 않는다. 새 저장은 별도 활성 `WishlistItem`을 만들고 기존 아카이브 기록은 변경하지 않는다.

## Archive 경계

구매 결정 시 목적과 모든 후보를 아카이브 시점의 별도 스냅샷으로 보존한다. 개별 후보 복원이나 삭제는 지원하지 않는다. 아카이브 취소는 목적과 모든 후보의 활성화, 구매 결정 취소, 아카이브 묶음 제거를 하나의 원자적 작업으로 처리한다. 아카이브 삭제도 묶음 전체를 대상으로 한다.

## 신뢰성 원칙

- DB 기록과 queue 등록의 불일치를 막기 위해 transactional outbox 또는 동등한 전달 보장 방식을 검토한다.
- Worker 작업은 중복 실행되어도 같은 최종 결과가 나와야 한다.
- retry 횟수, backoff, dead-letter 처리, 추출 성공률을 관측 가능하게 만든다.
- [추출 pipeline](extraction-pipeline.md)은 서버 구현의 보안 경계다.
