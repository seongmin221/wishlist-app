# B9 중복 비교와 선택 해소 구현 이력

> 2026-10-24 · 브랜치 `server/b9-duplicate-resolution` · 시작 `ef2077d`(B8 PR #24 병합) · [계약](../../../architecture/server/duplicate-resolution-api.md) · [정책](../../product-planning/mvp/decisions/b9-duplicate-resolution-policy-2026-10-24.md) · [설계](../../../superpowers/specs/2026-10-24-b9-duplicate-resolution-design.md) · [계획](../../../superpowers/plans/2026-10-24-b9-duplicate-resolution.md)

## 범위와 결과

구현 순서 문서 B9의 다섯 단계를 모두 구현했다: canonical·상품 식별 기준 → Product cache와 item snapshot 복사 → 중복 candidate/decision 저장 → DUP-01 → DUP-02. 동일 canonical만이 아니라 판매처가 다른 동일 상품(GTIN·브랜드+MPN)과 여러 후보(쌍별 판단)를 포함한다. 클라이언트 C6(비교 시트·안내 위치)와 B10 이후는 범위 밖이다.

| 커밋 | 내용 |
| --- | --- |
| `d98ab7e` | 식별 값(GTIN·brand+MPN) 추출·정규화, V22 `products`/`product_url_aliases`·item/job 컬럼, cache upsert/lookup, 가격 확인 시각 승계 |
| `52c4fd5` | 재분석은 cache를 쓰지 않음(신규 저장만 재사용) |
| `be9748f` | V23 `duplicate_candidates`/`duplicate_decision_receipts`, finish·재시도 소진에서 bases 재계산, kind 계산 SQL/Kotlin, 상품 DTO `duplicateCandidates` |
| `7401877` | DUP-01 조회·DUP-02 결정(ITEM-05 삭제 단계 분리·재사용), HTTP route |
| `bc59bcd` | 최종 리뷰 반영: 페이지를 못 읽은 기존 항목의 SAME_URL, 옵션별 Product의 식별 값 합의, V14 index 비교 baseline |

## 통과 조건별 검증(실제 PostgreSQL 16 Testcontainers)

| 조건 | 테스트 |
| --- | --- |
| 같은 URL의 새 key는 새 item, 같은 key는 replay | `ProductCacheTest` same url with a new key… |
| cache 재사용 뒤 owner별 category/purpose 재판단 | `ProductCacheTest` cache hits are classified for each owner(두 owner, 서로 다른 category·목적, fetch 1회) |
| 기존 item snapshot 불변(cache 갱신·item 편집 양방향) | `ProductCacheTest` item snapshots never follow the cache… |
| READY 7일·Complete만 저장·적중 시 미갱신·가격 확인 시각·alias·stale claim·browser lane·재분석 재fetch | `ProductCacheTest` 나머지 9건 |
| 기존 실패/처리 상태 안내(SAME_URL·AWAITING_ANALYSIS) | `DuplicateDetectionTest` failed and processing…, never read its page…; `DuplicateReadTest` kinds… |
| finish 밖 상태 변화에도 kind가 바로 바뀜 | `DuplicateDetectionTest` kinds follow analysis state changes outside finish |
| KEEP_BOTH 판단 영속(재분석 뒤에도 불변·재노출 없음) | `DuplicateDetectionTest` decided pairs survive reanalysis; `DuplicateDecisionTest` keep both… |
| 선택 삭제+판단 한 transaction | `DuplicateDecisionTest` a failure after the writes rolls back…(쓰기 뒤 예외 주입) |
| review 결정과 분리 | `DuplicateDecisionTest` review decisions and duplicate decisions stay independent |
| owner 격리 | `DuplicateDetectionTest` other owners never pair; `DuplicateReadTest`·`DuplicateDecisionTest` 다른 owner 404; route cursor owner 결합 |
| 잠금 순서·동시 삭제 | `DuplicateDecisionConcurrencyTest` ITEM-05 대기/선행, 제3 상품 finish와 직렬화, 두 쌍이 같은 상품 삭제, 같은 쌍 두 key |
| 다중 후보 | `DuplicateDetectionTest` several candidates and a chain; `DuplicateDecisionTest` several candidates are decided pair by pair |
| 멱등 replay | `DuplicateDecisionTest` a key replays…(new 삭제 뒤 포함); `DuplicateDecisionConcurrencyTest` same key sent twice at once |
| kind SQL·Kotlin 일치 | `DuplicateDecisionTest` the kotlin kind table matches the sql one(상태×canonical 유무×bases 부분집합 전수) |
| HTTP 계약 | `DuplicateRoutesTest` 4건(401/400/422/404/409, replay header, 서명 이미지) |

Task 3·4는 테스트를 먼저 썼지만, 다른 빌드가 동시에 돌아 실패 실행을 보기 전에 구현이 컴파일됐다. 대신 결함을 하나씩 넣어 테스트가 잡는지 확인했다. 넣은 결함은 kind 필터 제거, cursor의 item 결합 제거, DUP-02 kind 검사 제거, owner 잠금 제거, 원자성 seam 제거이며 모두 실패로 드러났다. 최종 리뷰 반영 2건은 RED→GREEN으로 확인했다.

## 전체 회귀

수정 전 실행(`--rerun-tasks`): 662 tests, 659 통과, 2 opt-in skip(`PlaywrightRealBrowserTest`, `RealUrlPilotTest`), 1 실패. 실패는 `WishlistReadIndexTest`였다. V14 baseline이 공용 projection의 `duplicate_candidates` join을 갖지 않았기 때문이며, `bc59bcd`에서 V17/V18 컬럼과 같은 방식으로 임시 골격을 두어 해결했다.

최종 실행(`bc59bcd`, `--rerun-tasks`): **664 tests, 662 통과, 2 opt-in skip, 실패·오류 0**. B8 마지막 기록(608 tests) 대비 B9 테스트 56건이 늘었다.

명령과 환경은 [로컬 테스트 환경](../../../architecture/server/local-test-environment.md)과 같다(JDK 17, `/var/run/docker.sock` Podman, `RUN_REAL_URL_PILOT=0`).

## 계획 대비 결정

- 식별 값은 추출 시점 값이고 사용자 편집과 무관하다. 병합은 가격처럼 page read가 있으면 교체한다. `failExhausted`도 식별 값을 병합하므로 bases 재계산을 그곳에서도 호출한다.
- 쌍에는 bases만 저장하고 kind는 실제 분석 상태로 계산한다(builder check 반영). 상태는 finish 밖에서도 바뀌지만 식별 값은 finish·재시도 소진에서만 바뀐다. SQL 식(`DuplicateKinds.kindSql`)과 Kotlin 판정(`kindOf`)은 같은 표이며 전수 parity 테스트로 묶었다.
- `SOURCE_URL`은 {정규화 공유 URL, canonical} 집합이 겹칠 때다. 그래서 canonical이 같으면 함께 붙는다.
- 상대 상품의 삭제·archive는 쌍 행을 바꾸지 않는다. 읽기에서 숨긴다. 그래서 ITEM-05·B8·B10 경로는 수정하지 않았다. 근거가 사라지는 경우 WITHDRAWN 처리는 상대가 ACTIVE일 때만 한다.
- cache 조회는 generation 1에만 적용한다. 재분석은 페이지를 다시 읽기 위한 명령이기 때문이다.
- DUP-02 숨김 kind(new가 다시 PROCESSING 등)는 404가 아니라 409 `DUPLICATE_DECISION_NOT_ALLOWED`다(spec 검사 순서).
- DUP-01과 DUP-02는 route·DTO·parser 파일을 공유하므로 한 커밋으로 묶었다.
- 판단 응답의 목적 요약은 B8 이동 응답과 같은 wire 형식(`MovedPurposeDto`)을 쓴다.

## 독립 리뷰

브랜치 전체를 fresh reviewer(Opus)가 검토했다. Critical은 없었다. 잠금 순서(owner 우선, 전역 `products`→alias 고정 순서), 멱등성, owner 격리, kind parity, pending 컬럼 정리·병합 경로는 문제가 없다고 확인했다.

| 지적 | 처리 |
| --- | --- |
| Important: 페이지를 못 읽은 PARTIAL 기존 항목(canonical 없음)이면 같은 URL 재저장이 숨겨짐 | 반영. SAME_URL 안내 |
| Important: 같은 이름 Product가 옵션별로 여럿이면 첫 node의 GTIN을 씀 → 다른 옵션끼리 MATCH | 반영. 모든 node가 같을 때만 |
| Minor: cache 출처는 적중 때만 기록 | 보류. 설계상 출처 기록은 복사한 경우의 추적용 |
| Minor: generation 1 ≠ 신규 저장(stale category 재예약은 generation 2) | 보류. 추가 fetch 비용만 있음 |
| Minor: 숫자형 `mpn`·전각 GTIN 무시 | 보류. 탐지 누락만 생기고 오탐은 없음. B11 노출률 측정 때 함께 본다 |
| Minor: 탐지 후보 조회의 OR 조건·legacy null 행 scan, `char(14)` 비교의 index 사용 | 보류. B11 부하 관측 |
| Minor: DUP-01이 후보마다 삭제 영향 조회(최대 50) | 보류 |
| Minor: "DUP-02 vs 기존 상품 자신의 재분석 finish" 경합을 제3 상품 finish로 검증 | 보류. 둘 다 owner 잠금으로 직렬화된다 |

## C6·B10·B11 인계

- **C6:** 분류·목적 확인 카드는 상품 DTO의 `duplicateCandidates`로 안내를 띄우고 DUP-01로 비교 시트를 연다. kind별 문구: MATCH는 "같은 상품으로 보이는 항목", SAME_URL은 "동일한 URL로 저장된 항목", AWAITING_ANALYSIS는 판단 대기다. 결정은 DUP-02이며, 확인창에는 지울 쪽 `deletionImpact`를 쓴다. 결정 뒤 카드의 결과 한 줄은 응답의 `candidate.decision`으로 표시한다. 검토에 들어가지 않는 새 항목(PARTIAL 등)의 안내 위치는 C6에서 정한다. 409 뒤에는 DUP-01을 다시 읽는다. replay body는 판단 당시 결과다.
- **B10:** archive된 상품의 쌍은 행이 남고 보이지 않는다. 전체 복원 때 OPEN 쌍이 다시 보이는 것이 맞는지, 오래된 bases를 다시 계산할지 정한다. archive된 상품에 대한 DUP-02 차단(404)을 종합 검증에 포함한다.
- **B11:** 실제 판매처의 GTIN/MPN 노출률을 측정한다. 판매처 간 탐지는 이 값에 달려 있다. `products`·alias·receipt 증가량, 탐지 후보 조회 비용과 index 사용, 전역 `products` 행 잠금 대기를 관측한다. B9 이전에 저장한 상품은 식별 값이 없으며 backfill하지 않았다(출시 전).
