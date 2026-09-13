# WishlistItem 상태 모델과 API 계약

> 상태: **확정** · 날짜: 2026-09-13 · 영역: **클라이언트·서버**

## 목적

공유받은 URL이 기기의 로컬 대기 항목에서 서버의 사용자별 `WishlistItem`으로 전환되고, 분석·재시도·직접 보완·검토·삭제를 거치는 동안의 상태와 API 경계를 정의한다.

이 문서는 목적·카테고리 자체의 CRUD와 아카이브 API 상세를 다루지 않는다. 다만 `WishlistItem`이 이 자원들을 참조하고 상태를 계산하는 데 필요한 필드는 포함한다.

## 설계 원칙

- 로컬 전송 명령과 서버 상품 자원을 같은 상태로 표현하지 않는다.
- 분석, 사용자 검토, 생명주기와 수동 보완은 독립된 상태 축으로 관리한다.
- 서버는 유효한 상태 전이와 경쟁 상황의 우선순위를 강제한다.
- 클라이언트는 서버 내부 작업 상태나 예외 세부사항에 의존하지 않는다.
- 같은 전송의 재시도는 중복 생성되지 않아야 하지만, 사용자가 같은 URL을 다시 공유하는 것은 새 항목으로 허용한다.
- MVP에서는 push, realtime, 주기적 polling과 오프라인 편집 명령 큐를 제공하지 않는다.

## 구성요소와 책임

```text
LocalSubmission --POST/idempotency--> WishlistItem
      |                                  |
      |                                  +--> AnalysisJob
      |                                  +<-- Product cache snapshot
      |                                  |
      +----------- KMP projection <------+--> category list / home actions
```

### LocalSubmission

`LocalSubmission`은 기기에만 존재하는 한 번의 URL 공유와 서버 전송 책임이다. 비로그인 또는 오프라인이어도 먼저 저장하며, 서버가 `WishlistItem`을 생성한 사실을 확인하기 전까지 유지한다.

주요 필드는 다음과 같다.

- `clientSubmissionId`: 공유할 때마다 생성하는 UUID. 같은 로컬 항목의 재전송에는 같은 값을 사용한다.
- `sourceUrl`: 사용자가 공유한 원본 URL.
- `createdAt`: 기기에서 공유를 수신한 시각.
- `accountBinding`: 비로그인 저장이면 비어 있고, 로그인 중 오프라인 저장이면 당시 계정에 고정한다.
- `submissionStatus`: `PENDING`, `SUBMITTING`, `ACCEPTED`.
- `serverItemId`: 서버가 수락한 뒤 받은 `WishlistItem` ID.
- `lastSubmissionError`: 마지막 전송 실패의 공개 가능한 클라이언트 상태.

전송 오류가 발생하면 새 로컬 항목을 만들지 않고 `PENDING`으로 돌아간다. 서버 응답을 받으면 한 로컬 transaction 안에서 서버 항목을 캐시에 upsert한 뒤 `LocalSubmission`을 제거한다. 이 순서로 앱이 중간에 종료돼도 같은 `clientSubmissionId`를 다시 보내 중복 없이 복구할 수 있다.

### WishlistItem

`WishlistItem`은 서버가 소유하는 사용자별 상품 snapshot이다. `Product` 캐시에서 metadata를 한 번 복사할 수 있지만 이후 어느 방향으로도 동기화하지 않는다.

식별과 동시성 필드:

- `id`, `ownerId`, `clientSubmissionId`
- `sourceUrl`, 선택적인 `productId`
- `version`: 사용자 변경의 optimistic concurrency에 사용하는 증가 정수. 사용자에게 보이는 서버 상태가 바뀔 때마다 증가한다.
- `createdAt`, `updatedAt`

표시용 snapshot 필드:

- 제품명, 대표 이미지, 가격, 통화, 판매처, 브랜드
- 예측·현재 카테고리와 값의 출처
- 예측·현재 목적과 값의 출처

상태 필드는 다음처럼 분리한다.

| 축 | 값 | 의미 |
| --- | --- | --- |
| `analysisStatus` | `PROCESSING`, `READY`, `PARTIAL`, `FAILED_RETRYABLE`, `FAILED_TERMINAL` | 서버 분석 pipeline의 결과 |
| `reviewStatus` | `NOT_REQUIRED`, `PENDING`, `CONFIRMED`, `DEFERRED` | 자동 카테고리·목적 결과에 대한 사용자 검토 |
| `lifecycleStatus` | `ACTIVE`, `ARCHIVED`, `DELETED` | 상품의 생명주기 |
| `manualCompletionAt` | nullable timestamp | 실패·누락 항목을 사용자가 직접 완료했는지 여부 |
| `categoryMissingReason` | `EXTRACTION_UNRESOLVED`, `CUSTOM_CATEGORY_DELETED`, null | 카테고리가 없는 원인 |

`FAILED_RETRYABLE`과 `FAILED_TERMINAL`에는 앱에 노출할 안정적인 `failureCode`를 저장한다. HTTP 응답, 대상 IP, parser stack trace 같은 내부 진단 정보는 `WishlistItem` 공개 모델과 분리한다.

### AnalysisJob

`AnalysisJob`은 사용자 상품이 아니라 Worker 실행을 관리하는 서버 내부 자원이다.

- `jobId`, `wishlistItemId`, `generation`
- `status`: queue 대기, 실행 중, 성공, 실패, 취소
- 내부 자동 재시도 횟수, 최대 횟수와 다음 실행 시각
- 내부 실패 분류와 진단 정보

하나의 사용자 분석 요청 안에서는 일시적 오류를 제한된 횟수만 자동 재시도한다. 이를 소진하면 항목을 `FAILED_RETRYABLE`로 전환하며, 사용자의 재분석 요청은 `generation`을 올린 새 작업을 만든다. Worker는 항목의 현재 generation과 자신의 generation이 같고 생명주기가 `ACTIVE`일 때만 결과를 반영한다.

### Product cache

canonical URL이 같은 `READY` Product cache 결과는 7일 동안 새 `WishlistItem`의 metadata snapshot으로 복사할 수 있다. Product cache의 `PARTIAL`과 실패 결과는 재사용하지 않는다. 캐시가 적중해도 카테고리와 목적은 새 사용자 항목을 기준으로 판단하므로 전체 분석이 끝날 때까지 `WishlistItem`은 `PROCESSING`이다.

## 상태 전이와 불변 조건

- 재분석은 `ACTIVE + FAILED_RETRYABLE + manualCompletionAt 없음`일 때만 허용한다.
- `FAILED_TERMINAL`에는 자동·사용자 재분석을 제공하지 않고 원인, 직접 보완과 삭제를 제공한다.
- 직접 보완 완료는 `ACTIVE`이면서 `PARTIAL`, `FAILED_RETRYABLE` 또는 `FAILED_TERMINAL`인 항목에만 허용한다. 제품명과 카테고리가 모두 필요하며, 완료 뒤 원래 `analysisStatus`와 진단 기록은 유지하고 `manualCompletionAt`을 기록하며 `reviewStatus`는 `CONFIRMED`가 된다.
- `PROCESSING` 항목은 삭제할 수 있지만 수정·직접 보완·재분석할 수 없다.
- 삭제는 현재 `version`과 무관하게 허용한다. 삭제된 항목에는 Worker 결과를 반영하지 않는다.
- 일반 편집, 직접 보완과 검토 결정은 `expectedVersion`을 요구한다. 값이 다르면 `409 Conflict`로 거절한다.
- `categoryId`가 있으면 `categoryMissingReason`은 null이어야 한다.
- 사용자 전용 카테고리 삭제로 카테고리가 없어진 경우에는 `CUSTOM_CATEGORY_DELETED`를 사용한다. 추출이 카테고리를 결정하지 못한 경우와 섞지 않는다.

## 홈 조치 상태

서버는 전체 상품을 클라이언트에 내려주지 않아도 홈 영역을 정확히 조회할 수 있도록 `ACTIVE` 항목에 한해 다음 우선순위로 `requiredAction`을 계산한다. 아카이브·삭제 항목은 홈 조회 대상에서 제외한다.

1. `PROCESSING`이면 `ANALYSIS_IN_PROGRESS`
2. 제품명이 없거나 `categoryMissingReason == EXTRACTION_UNRESOLVED`이면 `INFORMATION_COMPLETION`
3. `categoryMissingReason == CUSTOM_CATEGORY_DELETED`이면 `CATEGORY_REASSIGNMENT`
4. `reviewStatus == PENDING`이면 `CLASSIFICATION_REVIEW`
5. 그 외에는 `NONE`

서버 전송 전의 `ANALYSIS_PENDING`은 기기에만 존재하므로 KMP가 `LocalSubmission`에서 계산해 서버의 홈 응답과 합성한다. 하나의 서버 항목에는 하나의 `requiredAction`만 적용한다.

제품명과 카테고리가 있다면 `reviewStatus == PENDING`이어도 일반 카테고리 목록에 함께 표시할 수 있다. 홈 조치 상태와 일반 목록 포함 여부는 별도 조건이다.

## 목록 조회와 위치 유지

### 앱 신규 실행

앱 프로세스를 새로 시작하면 이전 viewport를 복원하지 않고 최신 상품부터 첫 window를 조회한다. MVP에서는 viewport 상태를 영구 저장하지 않는다.

### BG에서 FG로 복귀하거나 사용자가 새로고침

클라이언트는 화면 생존 기간에 다음 viewport 정보를 메모리에 유지한다.

- 목록 범위: 카테고리와 필터
- `anchorItemId`: 화면 중앙 또는 첫 번째로 충분히 보이는 항목
- `anchorCursor`: `createdAt + itemId` 정렬 키를 담은 서버의 불투명한 값
- `intraItemOffset`: anchor 카드 안의 상대 위치

복귀 또는 새로고침 시 서버에서 anchor 앞뒤 20개를 받고 stable item ID로 로컬 목록에 병합한 뒤 `anchorItemId + intraItemOffset`으로 위치를 유지한다. 절대 항목 번호나 전체 스크롤 offset은 삽입·삭제·가변 카드 높이에 취약하므로 기준으로 사용하지 않는다.

anchor 상품이 삭제되거나 다른 목록으로 이동했다면 서버는 `anchorCursor`와 가장 가까운 현재 항목을 반환하고 `anchorResolved=false`로 알린다. 클라이언트는 그 항목을 기준으로 복원한다.

목록의 앞뒤 끝에 접근하면 응답의 cursor로 다음 window를 추가 조회한다. 전체 활성 목록과 전역 변경분을 매번 동기화하는 protocol은 MVP에서 제공하지 않는다. 따라서 현재 불러오지 않은 window의 다른 기기 변경은 해당 범위를 다시 조회할 때 반영될 수 있다.

## API 계약

모든 API는 인증된 사용자 범위에서 동작한다. 다른 사용자의 자원은 존재 여부를 노출하지 않도록 `404 Not Found`로 처리한다.

### 상품 생성

```http
POST /v1/wishlist-items
Idempotency-Key: {clientSubmissionId}
```

```json
{
  "sourceUrl": "https://example.com/product",
  "clientCreatedAt": "2026-09-13T10:00:00Z"
}
```

서버는 사용자와 Idempotency-Key 조합을 유일하게 보장한다. 같은 key와 같은 요청을 다시 받으면 기존 항목을 반환한다. 같은 key를 다른 URL에 사용하면 `409 IDEMPOTENCY_KEY_REUSED`를 반환한다. URL이 같더라도 새 key이면 새 상품을 생성한다.

분석을 기다리지 않고 생성된 `WishlistItem` 표현, `Location` header와 `201 Created`를 반환한다. 같은 요청의 재전송이면 기존 표현과 `200 OK`, `Idempotency-Replayed: true`를 반환한다. 클라이언트는 두 응답을 모두 서버 생성 성공으로 처리한다.

### 카테고리 목록 window

```http
GET /v1/wishlist-items?categoryId={id}&limit=40
GET /v1/wishlist-items?categoryId={id}&anchor={cursor}&before=20&after=20
```

```json
{
  "items": [],
  "requestedAnchorItemId": "item-id",
  "resolvedAnchorItemId": "item-id",
  "anchorResolved": true,
  "previousCursor": "opaque-value",
  "nextCursor": "opaque-value"
}
```

정렬은 최근 저장순이며 같은 저장 시각은 item ID로 안정적으로 정렬한다. cursor 내부 형식은 API 계약으로 공개하지 않는다.

### 홈 조치 영역

```http
GET /v1/home/action-items?action={requiredAction}&cursor={cursor}&limit=20
```

서버가 `requiredAction`을 계산해 영역별 항목, `totalCount`와 다음 cursor를 반환한다. KMP는 여기에 기기의 `LocalSubmission`을 `ANALYSIS_PENDING`으로 합친다.

### 단일 상품 조회

```http
GET /v1/wishlist-items/{id}
```

상세 진입 또는 `409` 충돌 뒤 최신 상태를 가져온다.

### 재분석

```http
POST /v1/wishlist-items/{id}/analysis-attempts
Idempotency-Key: {attemptRequestId}
```

유효한 `FAILED_RETRYABLE` 항목을 `PROCESSING`으로 전환하고 새 generation의 작업을 만든다. 같은 사용자 행동의 전송 재시도에는 같은 key를 사용한다.

### 직접 보완 완료

```http
PUT /v1/wishlist-items/{id}/manual-completion
```

제품명, 카테고리, `expectedVersion`을 필수로 받고 이미지와 목적은 선택적으로 받는다. 상태 전이 조건을 서버가 검사한다.

### 분류·목적 검토

```http
PUT /v1/wishlist-items/{id}/review-decision
```

`CONFIRM` 또는 `DEFER`, 선택적인 카테고리·목적 수정과 `expectedVersion`을 받는다. 완료된 검토를 다시 여는 API는 MVP에서 제공하지 않는다.

### 일반 편집과 삭제

```http
PATCH /v1/wishlist-items/{id}
DELETE /v1/wishlist-items/{id}
```

`PATCH`는 전달된 제품명, 이미지, 카테고리와 목적만 변경하며 `expectedVersion`을 요구한다. `DELETE`는 version을 요구하지 않는다. 같은 사용자가 이미 삭제된 ID를 반복 삭제하면 `204 No Content`를 반환하며, 존재하지 않거나 다른 사용자의 ID는 `404 Not Found`로 처리한다.

### 공통 응답

```json
{
  "id": "wishlist-item-id",
  "clientSubmissionId": "uuid",
  "version": 7,
  "sourceUrl": "https://example.com/product",
  "product": {
    "name": "제품명",
    "imageUrl": null,
    "price": null,
    "currency": null
  },
  "category": {
    "id": "category-id",
    "source": "AI",
    "missingReason": null
  },
  "purpose": {
    "id": null,
    "source": "UNASSIGNED"
  },
  "analysis": {
    "status": "PARTIAL",
    "failureCode": null
  },
  "reviewStatus": "PENDING",
  "lifecycleStatus": "ACTIVE",
  "requiredAction": "CLASSIFICATION_REVIEW",
  "manualCompletionAt": null,
  "createdAt": "2026-09-13T10:00:01Z",
  "updatedAt": "2026-09-13T10:00:03Z"
}
```

## 오류 계약

API 오류는 안정된 `code`, 추적 가능한 `requestId`와 필요한 최소 `details`만 반환한다.

```json
{
  "error": {
    "code": "WISHLIST_ITEM_STATE_CONFLICT",
    "requestId": "safe-trace-id",
    "details": {
      "currentVersion": 8
    }
  }
}
```

- 앱은 `code`를 현지화된 문구와 행동으로 매핑한다.
- 알 수 없는 코드는 일반 오류로 안전하게 처리한다.
- `429`에는 `Retry-After`를 포함한다.
- 원본 예외, 응답 body와 보안 판단 세부사항은 공개하지 않는다.
- `FAILED_TERMINAL`의 `failureCode`에는 `BLOCKED_ADDRESS`, `UNSUPPORTED_CONTENT`, `ACCESS_DENIED`처럼 사용자가 이해할 수 있는 범주를 사용한다. 요청 형식 자체가 잘못된 URL은 상품을 만들지 않고 `422 INVALID_URL`로 응답한다.

## 경쟁 상황의 우선순위

| 경쟁 상황 | 우선되는 결과 | 보장 방법 |
| --- | --- | --- |
| 생성 응답 유실과 동일 요청 재전송 | 최초 생성 결과 | 사용자 + Idempotency-Key uniqueness |
| 이전 분석 완료와 새 재분석 | 새 generation | item ID와 generation 조건부 update |
| Worker 완료와 사용자 삭제 | 사용자 삭제 | `ACTIVE`일 때만 Worker 결과 반영 |
| 다른 기기 수정과 오래된 편집 | 먼저 반영된 최신 version | `expectedVersion`, `409 Conflict` |
| 카테고리 삭제와 해당 카테고리 선택 | 카테고리 삭제 | `CATEGORY_NOT_AVAILABLE`, 클라이언트 입력 초안 유지 |
| anchor 이동·삭제와 window 조회 | 현재 서버 정렬 | 가까운 위치와 `anchorResolved=false` 반환 |

## 새로고침 정책

- push, realtime과 주기적 polling을 사용하지 않는다.
- 앱 신규 실행 시 필요한 첫 window와 홈 영역을 조회한다.
- BG에서 FG로 복귀하면 현재 화면의 anchor window와 필요한 홈 영역을 한 번 갱신한다.
- 사용자가 새로고침하면 같은 범위를 다시 조회한다.
- 네트워크 복구 시 LocalSubmission 전송을 자동 재개하는 것은 서버 상태 polling과 별개다.

## 테스트 전략

### 도메인 단위 테스트

- 모든 허용·거절 상태 전이를 테이블 기반으로 검증한다.
- 상태 조합별 `requiredAction` 우선순위를 검증한다.
- 수동 완료, 재분석과 카테고리 삭제 불변 조건을 검증한다.

### API 계약 테스트

- 생성과 재분석의 idempotency, key 재사용 충돌을 검증한다.
- optimistic concurrency, 사용자 격리와 공개 오류 형식을 검증한다.
- 유효한 anchor, 사라지거나 이동한 anchor의 window 응답을 검증한다.

### PostgreSQL 통합 테스트

- 동시 생성에도 한 항목만 생성되는지 검증한다.
- Worker, 재분석과 삭제 경쟁에서 generation과 생명주기 조건을 검증한다.
- `WishlistItem` 생성과 작업 등록의 원자성을 검증한다.

### KMP와 플랫폼 테스트

- LocalSubmission이 앱 재시작과 계정 전환에서 올바르게 유지·격리되는지 검증한다.
- 서버 항목 저장 뒤에만 LocalSubmission이 제거되는지 검증한다.
- BG에서 FG로 복귀할 때 anchor item과 내부 offset이 유지되는지 검증한다.
- anchor 앞 삽입, anchor 삭제·이동과 가변 카드 높이를 검증한다.

### 핵심 end-to-end 테스트

1. 오프라인 공유에서 로그인·전송·분석 완료까지
2. 생성 응답 유실 뒤 재전송해도 항목 하나만 생성
3. 내부 재시도 소진 뒤 사용자가 재분석
4. 분석 실패 뒤 직접 보완하고 일반 목록 진입
5. 분석 중 삭제 뒤 늦은 Worker 결과 무시
6. BG에서 FG로 복귀하며 anchor window 갱신과 위치 유지

## 선택하지 않은 대안

### 하나의 상태 enum

구현은 처음에 단순하지만 분석·검토·생명주기·수동 보완 조합마다 상태가 늘어나므로 선택하지 않았다.

### Event sourcing

감사 이력과 재현성은 좋지만 MVP의 저장·운영 복잡도에 비해 이점이 작아 선택하지 않았다.

### 전체 활성 목록 재조회

상품 수 제한이 없는 상황에서 DB 조회, 직렬화, 네트워크와 로컬 병합 비용이 계속 증가하므로 선택하지 않았다.

### 전역 증분 sync cursor

다른 기기의 모든 변경과 tombstone을 정확히 동기화할 수 있지만, 현재 MVP는 오프라인 편집을 지원하지 않고 사용자가 보고 있는 window의 최신성이 우선이므로 보류한다.

### 항목 번호 또는 절대 scroll offset 복원

목록 앞의 삽입·삭제와 카드 높이 변경으로 같은 화면 위치를 보장하지 못하므로 stable anchor item과 내부 offset을 사용한다.

## 재검토 조건

- 불러오지 않은 window의 다중 기기 변경 때문에 사용자에게 잘못된 항목이 반복 노출되면 전역 변경 cursor와 tombstone 동기화를 도입한다.
- 오프라인 편집이 제품 요구가 되면 변경 명령 큐와 충돌 해결 정책을 별도로 설계한다.
- 분석 완료를 사용자가 늦게 인지하는 문제가 확인되면 제한적 foreground polling, realtime 또는 push를 비교한다.
- 사용자별 상품 규모와 홈 영역 조회량이 증가하면 window 크기, index와 API 분할을 측정 결과에 따라 조정한다.

## 관련 문서

- [제품 기능 및 스펙](../product-spec.md)
- [전체 서비스 구조](README.md)
- [Client 구조](client/README.md)
- [Server 구조](server/overview.md)
- [로컬 대기 저장과 분석 재시도 결정](../history/PRODUCT-PLANNING-DECISION-2026-09-10-LOCAL-PENDING-ANALYSIS.md)
- [ADR-005: WishlistItem 상태와 API 경계](../history/ADR-005-wishlist-item-state-api.md)
