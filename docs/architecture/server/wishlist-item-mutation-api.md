# 상품 사용자 변경 API (B7)

> 구현: B7 · 2026-10-14 · ITEM-04·05·06·07·08과 ITEM-03 `deletionImpact`

사용자가 저장한 상품을 편집·직접 보완·검토·삭제·재분석하는 계약이다. 결정은 [B7 제품·운영 결정](../../history/product-planning/mvp/decisions/b7-item-mutation-policy-2026-10-14.md), 설계 근거는 [B7 spec](../../superpowers/specs/2026-10-14-b7-item-mutations-design.md), 실행 기록은 [구현 이력](../../history/architecture/server/b7-item-mutations-implementation-2026-10-14.md)에 있다. 공통 상태·allowedActions는 [상태 API](../wishlist-item-state-api.md), 단일 상품 표현은 [B1 조회 계약](wishlist-item-read-api.md)을 따른다.

## 명령

| API | Method·path | body | 성공 |
| --- | --- | --- | --- |
| ITEM-04 | `PATCH /v1/wishlist-items/{id}` | `expectedVersion` + 편집 필드 하나 이상(`name`·`brand`·`categoryId`·`purposeId`·`mediaId`) | 200, 갱신된 단일 상품 |
| ITEM-07 | `PUT /v1/wishlist-items/{id}/manual-completion` | `expectedVersion`·`name`·`categoryId` 필수, `brand`·`purposeId`·`mediaId` 선택 | 200 |
| ITEM-08 | `PUT /v1/wishlist-items/{id}/review-decision` | `expectedVersion`·`decision`(`CONFIRM`/`DEFER`) 필수, `categoryId`·`purposeId` 선택 | 200 |
| ITEM-05 | `DELETE /v1/wishlist-items/{id}` | 없음(version 없음) | 204. 이미 삭제한 owner 상품도 204 |
| ITEM-06 | `POST /v1/wishlist-items/{id}/analysis-attempts` | 비어 있거나 `{}`. `Idempotency-Key` header 필수 | 200, PROCESSING 상품. 같은 key 재전송은 200 + `Idempotency-Replayed: true` |

## 필드 의미

- 생략은 유지, 명시적 `null`은 선택 값 해제다. `name`·`categoryId`는 null을 받지 않는다(category 해제는 B8 삭제에서만).
- `price`·`currency`·`sourceUrl`·`merchant` 등 다른 key는 422다.
- 상품명·브랜드: 1~200 code point, 한 줄(줄바꿈·탭·제어문자·보이지 않는 방향 제어 문자 거절), 공백만은 빈 값. **저장된 값과 다른 값만 검사한다.** 추출된 긴 이름을 그대로 보내도 거절하지 않는다. 텍스트는 보낸 그대로 저장한다.
- 같은 값을 보내면 그 필드는 바뀌지 않고 출처도 그대로다. 예외: 목적 `null`은 UNASSIGNED/AI에서 "목적 미지정 확정"(출처 USER)으로 바뀐다. ITEM-07의 이름·category는 같은 값도 USER로 기록한다.
- 바뀐 값은 USER 출처다. 브랜드 해제는 이후 분석이 다시 채우지 않는다. 이미 없는 브랜드에 null을 보내면 변경 없음이라 이후 분석이 채울 수 있다.
- 사진: `mediaId`는 owner의 READY media이고 다른 상품이 쓰지 않아야 한다. 연결하면 이미지 출처 USER. 해제하면 분석으로 얻은 외부 이미지로 돌아가고(출처 AI), 없으면 이미지 없음이다. 해제·교체·상품 삭제한 media는 24시간 뒤 정리된다.
- category·목적·사진이 저장값과 같으면 사용 가능 여부를 다시 검사하지 않는다.
- 실제 변경이 없으면 version을 올리지 않고 현재 표현을 돌려준다(ITEM-04).

## 상태별 허용

| 명령 | 조건 |
| --- | --- |
| ITEM-04 | ACTIVE·비PROCESSING. PARTIAL/실패도 가능하며 수동 완료 시각을 남기지 않는다 |
| ITEM-07 | ACTIVE + PARTIAL/FAILED_RETRYABLE/FAILED_TERMINAL + 수동 미완료. 결과: 수동 완료 시각, CONFIRMED, 분석 상태·실패 code 유지, 이후 재분석·검토·보완 불가 |
| ITEM-08 | ACTIVE + READY + 이름·category + PENDING + 수동 미완료. DEFERRED는 검토 영역에 다시 나오지 않는다 |
| ITEM-06 | ACTIVE + FAILED_RETRYABLE + 수동 미완료. 새 generation(합산 3회·30분 예산 새로 시작), 사용자 값·검토 상태 유지 |
| ITEM-05 | ACTIVE(PROCESSING 포함). ARCHIVED는 409 |

ITEM-04의 검토 상태: category 또는 목적을 바꾼 뒤 PENDING/DEFERRED 상품에 AI category와 AI 연결 목적이 하나도 남지 않으면 CONFIRMED가 된다. 하나라도 남으면 유지한다.

## 오류

| 순서 | 조건 | 응답 |
| --- | --- | --- |
| 1 | 인증 없음 | 401 `UNAUTHORIZED` |
| 2 | 경로 ID 형식 | 400 `INVALID_WISHLIST_ITEM_ID` |
| 3 | ITEM-06 key 형식 | 400 `INVALID_IDEMPOTENCY_KEY` |
| 4 | body 구조·key·타입·null | 422 `INVALID_WISHLIST_ITEM_INPUT`. 값 오류는 `details.fields`(정렬), 구조 오류는 빈 details |
| 5 | 없음·다른 owner·DELETED | 404 `WISHLIST_ITEM_NOT_FOUND` |
| 6 | `expectedVersion` 불일치 | 409 `WISHLIST_ITEM_VERSION_CONFLICT`, `details.currentVersion` |
| 7 | 허용되지 않는 상태 | 409 `WISHLIST_ITEM_STATE_CONFLICT`, `details.currentVersion` |
| 8 | 바뀐 이름·브랜드의 텍스트 규칙 | 422 `INVALID_WISHLIST_ITEM_INPUT`, `details.fields` |
| 9 | 바뀐 참조 사용 불가(category → 목적 → 사진 순서로 첫 위반) | 409 `CATEGORY_NOT_AVAILABLE` / `PURPOSE_NOT_AVAILABLE` / `MEDIA_NOT_AVAILABLE` |
| 10 | 재분석 owner별 직전 60초 10건 초과 | 429 `WISHLIST_ITEM_REANALYSIS_RATE_LIMITED`, `Retry-After` |

ITEM-06 replay는 상태·한도 검사보다 먼저다. 같은 key를 다른 상품에 쓰면 409 `IDEMPOTENCY_KEY_REUSED`, replay 대상이 삭제됐으면 404다. 재분석 key는 계정 데이터가 유지되는 동안 보존한다.

## deletionImpact

단일 상품 응답(ITEM-01 생성·재전송, ITEM-03, ITEM-04·06·07·08)에만 있고 목록 카드(ITEM-02, HOME)에는 key가 없다. ARCHIVED와 삭제 tombstone에도 없다.

```json
"deletionImpact": { "purpose": { "id": "uuid", "name": "출퇴근 헤드폰", "candidateCount": 3, "remainingCount": 2 } }
```

목적 미지정이면 `{ "purpose": null }`. `candidateCount`는 그 목적의 ACTIVE 상품 수(PUR-01/03과 같음), `remainingCount = candidateCount - 1`이며 0이면 클라이언트가 빈 목적 유지를 안내한다. 상품과 영향은 같은 snapshot(상세는 REPEATABLE READ, 변경 응답은 owner 잠금 아래 쓰기 transaction)에서 읽는다. 확인창 이후 다른 기기 변경은 ITEM-05가 version을 받지 않는 기존 계약대로 반영되지 않을 수 있다.

## 동시성과 늦은 Worker

- 모든 변경은 transaction 하나에서 owner → 목적(ID순) → 상품 → 사진(ID순) → job 순서로 잠근다. owner 잠금은 B2~B6과 같은 `FOR UPDATE`다.
- 같은 version의 동시 변경은 하나만 성공하고 나머지는 VERSION 409다.
- 삭제는 진행 중 job(`GENERAL_/BROWSER_` PENDING·RUNNING)만 취소하고 실행 identity를 해제한다. 끝난 job 이력은 남는다. 이후 Worker 결과·claim·복구는 상품을 바꾸지 않는다. category·목적 참조는 tombstone에 남는다.
- 재분석 뒤 이전 generation의 성공·실패·재시도는 무시된다. 같은 key 동시 재분석은 job 하나다.
- 재분석 commit 뒤 발행이 실패해도 응답은 성공이고 maintenance가 발행한다. 요청 취소는 전파되며, commit된 시도는 같은 key 재전송으로 받는다.

## 저장 (V19)

`mutation_receipts.wishlist_item_id`(owner 복합 FK → `wishlist_items(owner_id,id)` unique)를 추가하고 receipt 대상은 category·목적·상품 중 정확히 하나다. operation은 `REANALYZE_WISHLIST_ITEM`이다.
