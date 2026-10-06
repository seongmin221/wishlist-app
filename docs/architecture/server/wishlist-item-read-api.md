# B1 상품 생성·재전송·상세 조회

## 조회와 응답 경계

`GET /v1/wishlist-items/{id}`와 생성·재전송은 `WishlistItemRepository`의 동일 owner-scoped projection을 읽고 `app.http.WishlistItemViewMapper`로 직렬화한다. 한 SQL에서 상태·version·metadata·출처·시각을 함께 읽어 서로 다른 version의 필드가 섞이지 않게 한다. `requiredAction`과 `allowedActions`는 기존 `WishlistItemPolicy`를 사용한다. mapper는 HTTP presenter이므로 도메인이 HTTP DTO를 참조하지 않는다.

`Main`에서 생성·조회 서비스를 각각 조립해 route에 필수 주입한다. 생성 서비스 내부 repository를 조회 서비스 조립에 사용하지 않고, 조회 callback 기본값도 두지 않는다. repository는 생성의 item/job/outbox SQL과 owner/key 조회를 담당한다. key 충돌의 기존 항목은 key 기준 projection 한 번으로 읽으며 상태만 반환하는 미사용 조회 API는 제거했다. 생성 서비스는 transaction과 결과·발행 순서를 조정한다.

인증이 없으면 `401 UNAUTHORIZED`, UUID 형식이 잘못되면 `400 INVALID_WISHLIST_ITEM_ID`다. 존재하지 않음·다른 owner·DELETED는 모두 `404 WISHLIST_ITEM_NOT_FOUND`다. ARCHIVED는 조회할 수 있지만 조치는 없다. 생성 key 재전송에는 기존 삭제 tombstone을 반환한다. GET과 생성·발행 JDBC 작업은 `Dispatchers.IO`에서 실행하고 취소를 전파한다.

공개 projection은 이름·이미지·각 값 출처, category/purpose의 현재 ID·출처·누락 사유, analysis/review/lifecycle, version, 수동 완료 시각, 원본 URL과 저장/변경 시각을 반환한다. DB에 없는 가격·통화·brand·merchant·metadataCheckedAt은 기존 DTO의 nullable 필드로 유지한다. `classified_at`은 AI 분류 시각이므로 metadata 확인 시각으로 대신 사용하지 않는다. 목적명·후보 수를 사용하는 deletionImpact는 목적 연결이 구현되는 B3/B8에서 확장한다.

## 공유 시각과 생성 key

`sourceUrl`은 최대 2048자(UTF-16 길이)이며 UTF-8로 인코딩할 수 없는 문자열(짝 없는 surrogate)도 거절한다. 둘 다 새 오류 code 없이 기존 `422 INVALID_URL`이며 DB에 쓰기 전에 검사한다. JDBC가 짝 없는 surrogate를 `?`로 바꿔 저장하면 같은 key의 재전송이 저장 URL과 달라져 409가 되기 때문이다. 정상 surrogate 쌍(예: 이모지)은 허용하며 재전송이 일치한다. 요청 body 전체 상한은 여전히 운영 설정 범위의 후속 항목이다.

`clientCreatedAt`은 선택 문자열이며 생략과 JSON null은 동일하게 null로 저장한다. 순수 `parseCreateRequest` 함수가 JSON wire 타입과 시각을 검증하고 URL의 허용 여부는 생성 서비스가 검사한다. 시간대가 있는 ISO 8601 날짜/시각을 Instant로 변환하며, 현지 시각과 UTC Instant 양쪽 연도가 1~9999여야 한다. 날짜만·offset 없는 시각·잘못된 날짜·문자열 이외의 값은 `422 INVALID_CLIENT_CREATED_AT`다. 기기 시계 오차를 이유로 미래 시각을 거절하지 않는다.

V10은 `client_created_at timestamptz`만 추가하고 기존 행은 null로 유지한다. 입력은 microsecond 아래 자릿수를 절삭해 PostgreSQL 반올림으로 UTC 연도 10000에 넘어가지 않게 한다. JDBC는 `setObject(OffsetDateTime)`/`getObject(OffsetDateTime::class.java)`로 공유 시각을 기록·조회해 1582년 이전 날짜도 Gregorian 기준 Instant를 유지한다. 응답은 DB에 보관한 값을 UTC로 반환한다. 서버 `createdAt`과 `(created_at,id)` 정렬은 유지한다. 같은 owner/key/URL의 유효 재전송은 최초 공유 시각을 덮어쓰지 않는다. 같은 key와 다른 URL은 기존대로 409이고, 같은 URL과 다른 key는 새 상품이다. GET ID와 Idempotency-Key는 같은 UUID parser로 정규 36자 형식을 확인하며 대소문자는 허용한다.

## 공개 실패 코드

도메인 `AnalysisFailureCode` enum이 공개 여부와 category 누락 사유를 정의한다. Worker의 새 실패 저장은 enum을 받고, 최종 반영의 category 사유와 HTTP 응답도 같은 enum을 사용한다. V8 SQL은 당시 migration 기록이므로 수정하지 않는다. PROCESSING/READY의 failureCode는 null이다. PARTIAL·실패에는 아래 허용 code를 반환한다.

- `BLOCKED_ADDRESS`, `UNSUPPORTED_CONTENT`, `ACCESS_DENIED`
- `AI_ABSTAINED`, `AI_UNUSABLE_RESPONSE`, `AI_INVALID_CANDIDATE`, `AI_USAGE_OUT_OF_RANGE`
- `AI_BUDGET_EXCEEDED`, `AI_CONFIGURATION_ERROR`

실패인데 저장 code가 없거나 알려지지 않았으면 FAILED_RETRYABLE은 `ANALYSIS_RETRYABLE_FAILURE`, FAILED_TERMINAL은 `ANALYSIS_FAILED`다. PARTIAL의 알 수 없는 code도 `ANALYSIS_FAILED`로 가리고, code가 없으면 null을 유지한다. 원본 내부 진단을 공개하지 않으며 DB 진단값을 바꾸지는 않는다. B5에서 추출·실행 실패 분류를 더 구체화한다.

`BLOCKED_ADDRESS`/`UNSUPPORTED_CONTENT`/`ACCESS_DENIED`는 기존 공개 계약의 범주를 유지하는 것이며 현재 extraction이 이 code를 저장한다는 의미는 아니다. 구체적인 producer 연결은 B5에서 구현한다.

## commit 후 지정 발행

생성 transaction은 item·job·outbox event를 함께 commit한다. 생성 연결을 반환한 뒤 event ID를 callback에 전달하고 `OutboxDispatcher.dispatchEvent(id)`가 해당 event만 claim한다. 오래된 retry backlog가 새 생성의 즉시 발행을 대신 소비하지 않는다. 지정 발행과 batch 발행은 DB 시각 기반 120초 lease·SKIP LOCKED·결정적 task 이름을 공유한다. 없는 event·발행 완료·살아 있는 lease는 발행하지 않는다.

발행 실패는 가능한 경우 lease를 해제하고 item과 미발행 event를 보존한다. 취소도 lease 해제 후 전파한다. key 재전송은 저장된 API 결과를 복구하며 발행을 다시 시도하지 않는다. 미발행 event의 queue 전달 복구는 B5 Scheduler에서 연결한다. 생성 응답은 transaction 안에서 읽고 성공적으로 commit한 snapshot을 반환하며 발행 후 재조회하지 않는다. post-commit 재조회 장애로 201/Location을 잃는 경로를 없앤다. 성공적인 생성·즉시 발행의 pool 대여는 생성 transaction, claim, published update의 세 번이다. Worker가 그 사이 완료하면 이후 GET/재전송에서 최신 결과를 읽는다. runtime 종료 gate와 작은 connection pool을 유지한다.

B0의 production createTask RPC 5초 상한과 batch 발행 메서드를 유지한다. B5의 Scheduler·장기 PENDING 복구·batch의 후보별 시간/실패 격리·generation 전체 retry 예산은 후속 범위다.

Scheduler가 있는 상태의 비동기 발행 전환과 API/Worker body 크기 제한은 운영 정책·설정 범위의 후속 검토 항목이다. 이번 리뷰 보완은 fire-and-forget 작업이나 임의 body 상한을 추가하지 않는다.
