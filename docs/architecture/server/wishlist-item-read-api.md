# B1 상품 생성·재전송·상세 조회

## 조회와 응답 경계

`GET /v1/wishlist-items/{id}`와 생성·재전송은 `WishlistItemStateRepository`의 동일 owner-scoped projection을 읽고 `WishlistItemViewMapper`로 직렬화한다. 한 SQL에서 상태·version·metadata·출처·시각을 함께 읽어 서로 다른 version의 필드가 섞이지 않게 한다. `requiredAction`과 `allowedActions`는 기존 `WishlistItemPolicy`를 사용한다.

인증이 없으면 `401 UNAUTHORIZED`, UUID 형식이 잘못되면 `400 INVALID_WISHLIST_ITEM_ID`다. 존재하지 않음·다른 owner·DELETED는 모두 `404 WISHLIST_ITEM_NOT_FOUND`다. ARCHIVED는 조회할 수 있지만 조치는 없다. 생성 key 재전송에는 기존 삭제 tombstone을 반환한다. GET과 생성·발행 JDBC 작업은 `Dispatchers.IO`에서 실행하고 취소를 전파한다.

공개 projection은 이름·이미지·각 값 출처, category/purpose의 현재 ID·출처·누락 사유, analysis/review/lifecycle, version, 수동 완료 시각, 원본 URL과 저장/변경 시각을 반환한다. DB에 없는 가격·통화·brand·merchant·metadataCheckedAt은 기존 DTO의 nullable 필드로 유지한다. `classified_at`은 AI 분류 시각이므로 metadata 확인 시각으로 대신 사용하지 않는다. 목적명·후보 수를 사용하는 deletionImpact는 목적 연결이 구현되는 B3/B8에서 확장한다.

## 공유 시각과 생성 key

`clientCreatedAt`은 선택 문자열이며 생략과 JSON null은 동일하게 null로 저장한다. 전달하면 시간대가 있는 ISO 8601 날짜/시각을 받아 Instant로 변환한다. 연도는 1~9999이며 날짜만·offset 없는 시각·잘못된 날짜·문자열 이외의 값은 `422 INVALID_CLIENT_CREATED_AT`다. 기기 시계 오차를 이유로 미래 시각을 거절하지 않는다.

V10은 `client_created_at timestamptz`만 추가하고 기존 행은 null로 유지한다. 응답의 `clientCreatedAt`은 DB에 보관한 값을 UTC로 반환하며 PostgreSQL의 microsecond 정밀도를 따른다. 서버 `createdAt`과 `(created_at,id)` 정렬은 유지한다. 같은 owner/key/URL의 유효 재전송은 최초 공유 시각을 덮어쓰지 않는다. 같은 key와 다른 URL은 기존대로 409이고, 같은 URL과 다른 key는 새 상품이다.

## 공개 실패 코드

PROCESSING/READY의 failureCode는 null이다. PARTIAL·실패에는 아래 허용 code만 원문대로 반환한다.

- `BLOCKED_ADDRESS`, `UNSUPPORTED_CONTENT`, `ACCESS_DENIED`
- `AI_ABSTAINED`, `AI_UNUSABLE_RESPONSE`, `AI_INVALID_CANDIDATE`, `AI_USAGE_OUT_OF_RANGE`
- `AI_BUDGET_EXCEEDED`, `AI_CONFIGURATION_ERROR`

실패인데 저장 code가 없거나 알려지지 않았으면 FAILED_RETRYABLE은 `ANALYSIS_RETRYABLE_FAILURE`, FAILED_TERMINAL은 `ANALYSIS_FAILED`다. PARTIAL의 알 수 없는 code도 `ANALYSIS_FAILED`로 가리고, code가 없으면 null을 유지한다. 원본 내부 진단을 공개하지 않으며 DB 진단값을 바꾸지는 않는다. B5에서 추출·실행 실패 분류를 더 구체화한다.

## commit 후 지정 발행

생성 transaction은 item·job·outbox event를 함께 commit한다. 생성 연결을 반환한 뒤 event ID를 callback에 전달하고 `OutboxDispatcher.dispatchEvent(id)`가 해당 event만 claim한다. 오래된 retry backlog가 새 생성의 즉시 발행을 대신 소비하지 않는다. 지정 발행과 batch 발행은 DB 시각 기반 120초 lease·SKIP LOCKED·결정적 task 이름을 공유한다. 없는 event·발행 완료·살아 있는 lease는 발행하지 않는다.

발행 실패는 가능한 경우 lease를 해제하고 item과 미발행 event를 보존한다. 취소도 lease 해제 후 전파하며 key 재전송으로 복구할 수 있다. 즉시 발행을 시도한 후 현재 item을 다시 읽어 생성 응답에 반영한다. Worker 완료를 기다리는 구조는 아니며 응답은 마지막 조회 시점의 snapshot이다. runtime 종료 gate와 작은 connection pool을 유지한다.

B0의 production createTask RPC 5초 상한과 batch 발행 메서드를 유지한다. B5의 Scheduler·장기 PENDING 복구·batch의 후보별 시간/실패 격리·generation 전체 retry 예산은 후속 범위다.
