# B4 상품 목록·홈 조회 구현 이력

> 2026-10-07 · 설계·계획 승인 · Native 구현 중

## 수신과 baseline

PR #11 B3 병합을 포함한 **origin/develop `ed1eef9`**에서 만든 독립 Orca worktree `seongmin221/server-b4-item-list-home`에서 시작했다. 시작 시 clean을 확인했다. 로컬 develop(`59c11cc`)은 기준으로 사용하지 않았다. 범위는 ITEM-02 → HOME-02 → HOME-01이다.

이 공간에서 JDK 17로 전체 `./gradlew test --rerun-tasks`를 직접 실행했다. 결과는 exit 0, `BUILD SUCCESSFUL in 5m 36s`, tests=296, failures=0, errors=0, skipped=1이다. **295 통과·RealUrlPilot 1 skip**이며 이전 B3 결과를 재사용하지 않았다. 실제 외부 URL pilot·production 검증은 수행하지 않았다.

실행에는 Podman socket(`/var/run/docker.sock`)을 사용했다. 명령·환경 확인 근거·재현 절차는 [로컬 테스트 환경](../../../architecture/server/local-test-environment.md#b4-baseline의-실행-환경-기록)에 남겼다.

## 정책 확정과 설계 준비

홈 최근 목적 최대 3개·빈 목적 포함, 기기 skip 초기화 restart, 목적 미지정의 모든 ACTIVE 후보, category leaf 조회와 이름 존재 기준을 사용자에게 확인했다. 추가로 **CAT-01/02 count를 ITEM-02 표시 집합에 맞추고 HOME-01 별도 할 일 합계 필드를 두지 않는 권장안**을 사용자가 선택했다. 판단 근거와 제품 문서 반영은 [제품 결정](../../product-planning/mvp/decisions/b4-read-api-policy-2026-10-07.md)에 남겼다.

## 문서·코드 대조

HOME-02의 기존 action/cursor/limit 예시에 없는 anchor 입력·응답, 정보 보완 그룹, nullable metadata의 B5 경계, 공용/custom category index, 이름 blank 판정 차이는 [B4 설계 spec의 대조 절](../../../superpowers/specs/2026-10-07-b4-read-api-design.md#기존-계약과-코드-대조)로 옮겼다.

## 설계 spec

[상품 목록·홈 조회 설계](../../../superpowers/specs/2026-10-07-b4-read-api-design.md)에 group query, page/window 크기, 삭제·이동 anchor 복구, 카드와 cursor, snapshot·index·검증을 제안했다.

설계 피드백을 반영해 B3 형태의 구조/범위 검증 cursor로 단순화하고 신규 secret 제안을 제거했다. ITEM-02의 기존 anchor query와 requestedAnchorItemId를 유지하며 HOME-02 확장과 대체 항목 우선순위를 계약 비교표에 구분했다. 표시용 count와 B8 삭제 영향의 집계를 분리하고, 홈 SQL·index 교체 검토·Unicode 공백 상수·DB 유효 조합 parity·반대 방향 EXISTS를 구체화했다. 상세 설계와 근거는 spec에 모았다. 2026-10-07 사용자 지시 ‘계획 진행해’로 수정한 설계를 승인받았다. [작업별 계획](../../../superpowers/plans/2026-10-07-b4-read-api.md)은 Task1~9의 인터페이스·RED/GREEN·회귀·EXPLAIN·독립 리뷰와 커밋을 정의했다. 사용자가 권장 Native 실행을 선택해 작업별 TDD를 진행한다.

## Task 1 — 공통 조회 판정

신규 코드 부재로 compile RED를 확인한 뒤 공통 scope/window 타입과 SQL requiredAction/group·표시용 visibility를 추가했다. JDK Char 전체와 공백 상수 28개를 대조하고 실제 DB 제약을 유지한 10,080개 상태 조합과 이름 공백 경계를 policy와 비교했다. 작업별 17개 테스트를 실행해 통과했다.

첫 GREEN 실행은 공백 category fixture가 V11 public FK에 막혀 실패했다. spec의 ‘저장 가능한 공백 category’ 가정을 바로잡고 제약을 유지했다. 유효 DB 조합 parity와 별도의 SELECT-derived row 표현 parity로 구분해 검증했다. 실패 실행을 통과로 기록하지 않았다.

## Task 2~3 — cursor와 목록 window

Cursor/query 부재의 compile RED를 확인하고 owner·scope·endpoint·용도 검증과 입력 상한을 구현했다. Cursor/parser·B3 PurposeRoutes 8개 테스트가 통과했다. HMAC이나 신규 secret은 없다. 구조가 유효한 자기 범위 위치 조작을 인증 수단으로 다루지 않으며 SQL owner/scope가 권한을 강제한다.

상품 row projection을 상세·replay·카드에 공유하고 목록 count·keyset·양방향 존재 확인·anchor 복구를 같은 snapshot으로 읽는다. 공통 mapper와 10,080개 상태 조합도 대조했다. 동일 created_at의 PostgreSQL UUID 순서와 Java signed 비교 경계, 앞뒤 페이지 왕복, anchor 삭제·category/목적 이동·맨 끝·빈 목록, PROCESSING 최소 정보 유지, 목적 count 일치, B1 상세·생성 회귀를 포함한 25개 테스트가 통과했다.

## Task 4 — category 표시 count

CAT-01/02의 공용/custom count에 ITEM-02와 같은 표시 predicate를 적용했다. B8 삭제 영향은 이름 누락을 포함한 ACTIVE 전체 집계이며 표시 count를 재사용하지 않는다. 기존 B2의 목록 count 테스트와 owner/FK 테스트는 이름 없는 ACTIVE를 count=1로 기대했으므로 확정 B4 정책에 따라 count=0 확인 후 이름을 넣어 count=1 확인을 추가했다. FK·owner 격리·version·중복 검증은 유지했다. 새 RED 테스트는 기존 전체 집계의 0/2와 1/3 불일치를 재현했고 category·HTTP 회귀는 최종 실행에서 통과했다.

## Task 5~7 — ITEM/HOME HTTP와 snapshot

ITEM-02는 기존 POST와 공존하므로 route 부재 RED는 404가 아닌 405였다. auth·입력·cursor 오류와 상세와 같은 카드 projection의 최종 GREEN을 확인했다. HOME-02는 group query로 세 보완 action을 묶고 완료한 카드가 빠지는 anchor 복구를 정상 응답으로 반환한다. ITEM HTTP+B1 회귀16개, HOME action+목록 회귀9개가 통과했다.

HOME-01은 MATERIALIZED classified의 공통 판정에서 FILTER count와 group별 row_number 미리보기 key를 한 번에 읽는다. 카드 projection은 최대12개 key를 한 batch로 조회하고, 최근 ACTIVE 목적3개와 B3 후보4개를 같은 repeatable-read connection에서 읽는다. latch로 첫 SELECT 후 다른 connection의 상품/purpose 변경 commit을 재현해 이전 응답 snapshot 유지와 다음 응답 갱신을 확인했다. 홈·B3 관련9개 테스트가 통과했다.

## Task 8 — V15 인덱스와 실제 SQL 측정

`WishlistReadIndexTest`는 같은 DB를 V14→V15로 migration하며 repository가 실행한 SQL과 bind 값을 proxy로 기록해 그대로 EXPLAIN(ANALYZE,BUFFERS,FORMAT JSON)한다. 쿼리 복사본이나 planner 강제 설정을 사용하지 않았다. 환경은 JDK17, Podman, PostgreSQL16.15 aarch64 Alpine이다. V1~V14 파일은 수정하지 않았다.

Fixture 생성식은 owner2명 × 10,000행이다. 각 owner의 public/custom 각5,000·purpose 지정/미지정 각5,000·ACTIVE 전체10,000, 조치 그룹 각100·NONE9,700이다. `n=0..9999`, `n%100=0/1/2`를 각각 분석/보완/검토로 두고 `n%2`로 category, `n%4<2`로 목적 지정 여부를 나눈다. 저장 시각은 `2026-10-07T10:00:00Z - floor(n/20)초`, ID는 `UUID(owner번호,n+1)`이므로 동일 시각20개를 포함한다. Kotlin grouping으로 이 가정을 검증하고 ANALYZE 후 측정했다. 페이지 key는 limit41(+1 포함), anchor 양방향은 해당 scope의 101번째 key를 boundary로 limit21, 홈은 limit21이다. owner A·생성한 category/purpose ID·boundary·공백 상수는 V14/V15 사이 동일하다.

아래 시간은 한 실행의 관측값(ms)이며 처리량 보장이나 자동 테스트의 시간 assertion이 아니다. scan은 wishlist_items scan node의 반환행+filter 제거행이며, seq scan에서는 다른 owner의 제거행도 포함한다. shared는 hit/read block, 전체 측정의 temp read/write는 모두0이었다.

| 실제 query | ms (V14→V15) | scan 행 | shared hit/read | sort | V15 선택 index |
| --- | --- | --- | --- | --- | --- |
| public-page | 1.853 → 0.051 | 5000 → 41 | 282/0 → 7/3 | [top-N heapsort] → [] | [wishlist_active_public_category_order] |
| public-anchor-older | 1.618 → 0.046 | 5000 → 21 | 276/0 → 7/0 | [top-N heapsort] → [] | [wishlist_active_public_category_order] |
| public-anchor-newer | 1.276 → 0.025 | 5000 → 21 | 276/0 → 7/0 | [top-N heapsort] → [] | [wishlist_active_public_category_order] |
| custom-page | 1.488 → 0.038 | 5000 → 42 | 276/0 → 7/3 | [top-N heapsort] → [] | [wishlist_active_custom_category_order] |
| custom-anchor-older | 1.475 → 0.029 | 5000 → 22 | 276/0 → 8/1 | [top-N heapsort] → [] | [wishlist_active_custom_category_order] |
| custom-anchor-newer | 1.186 → 0.020 | 5000 → 21 | 276/0 → 7/0 | [top-N heapsort] → [] | [wishlist_active_custom_category_order] |
| purpose-page | 0.048 → 0.035 | 41 → 41 | 10/0 → 10/0 | [] → [] | [wishlist_active_purpose] |
| purpose-anchor-older | 0.022 → 0.025 | 21 → 21 | 8/0 → 8/0 | [] → [] | [wishlist_active_purpose] |
| purpose-anchor-newer | 0.026 → 0.017 | 21 → 21 | 7/0 → 7/0 | [] → [] | [wishlist_active_purpose] |
| unassigned-page | 1.290 → 0.037 | 10000 → 81 | 280/0 → 7/2 | [top-N heapsort] → [] | [wishlist_active_owner_order] |
| unassigned-anchor-older | 1.219 → 0.030 | 10000 → 41 | 280/0 → 6/1 | [top-N heapsort] → [] | [wishlist_active_owner_order] |
| unassigned-anchor-newer | 0.047 → 0.019 | 100 → 43 | 19/0 → 6/0 | [top-N heapsort] → [] | [wishlist_active_owner_order] |
| home-ANALYSIS_IN_PROGRESS-page | 14.390 → 3.052 | 10000 → 2020 | 280/0 → 156/13 | [top-N heapsort] → [] | [wishlist_active_owner_order] |
| home-ANALYSIS_IN_PROGRESS-count | 14.401 → 14.664 | 10000 → 10000 | 280/0 → 287/57 | [] → [] | [wishlist_active_owner_order] |
| home-INFORMATION_COMPLETION-page | 14.339 → 3.130 | 10000 → 2019 | 280/0 → 169/0 | [top-N heapsort] → [] | [wishlist_active_owner_order] |
| home-INFORMATION_COMPLETION-count | 14.504 → 14.122 | 10000 → 10000 | 280/0 → 344/0 | [] → [] | [wishlist_active_owner_order] |
| home-CLASSIFICATION_REVIEW-page | 14.327 → 2.979 | 10000 → 2018 | 280/0 → 169/0 | [top-N heapsort] → [] | [wishlist_active_owner_order] |
| home-CLASSIFICATION_REVIEW-count | 14.613 → 14.524 | 10000 → 10000 | 280/0 → 344/0 | [] → [] | [wishlist_active_owner_order] |
| home-summary | 17.139 → 17.487 | 10000 → 20000 | 283/0 → 550/0 | [quicksort] → [quicksort] | [] |
| cat-custom-count | 1.493 → 1.682 | 5000 → 5000 | 278/0 → 277/45 | [] → [] | [custom_categories_owner_id_parent_id_display_order_key, wishlist_active_custom_category_order] |
| cat-public-count | 1.631 → 1.759 | 5000 → 5000 | 276/0 → 274/40 | [] → [] | [wishlist_active_public_category_order] |
| purpose-summary | 0.575 → 0.585 | 5000 → 5000 | 359/0 → 359/0 | [] → [] | [purposes_active_activity, wishlist_active_purpose] |

공용/custom의 owner/category/created_at/id ACTIVE index는 정렬을 제거하고 V11의 짧은 prefix 경로도 대체하므로 V15에서 짧은 두 index를 drop했다. CAT 집계는 새 prefix를 선택했지만 넓어진 index의 block/시간 비용이 소폭 증가했다. B3 목적 summary는 V13을 그대로 쓴다. Worker의 item PK·job/claim·outbox index는 바꾸지 않았고 전체 회귀로 검증한다.

홈 owner/order index는 page scan을 약2,020행으로 줄여 추가했다. group 조건은 여전히 filter이므로 count는 owner 전체를 읽고 HOME-01은 classified용 base scan1회가 필요하다. HOME-01의 관측 plan은 seq scan으로 바뀌어 두 owner20,000행을 읽었으며 count/summary 속도 개선을 주장하지 않는다. write마다 더 큰 category index 두 개와 홈 index 유지 비용이 추가된다.

목적 미지정 전용 index는 추가하지 않았다. V13 btree는 NULL을 저장하며 IS NULL 접근이 가능하다. 이번 50% NULL 분포에서는 V14 미지정 page가 짧은 category index+sort, V15가 홈 order index+filter를 선택했다. V13 재사용 가능성과 실제 planner 선택을 구분한다.

V15 부재 RED는 `missing wishlist_active_public_category_order`였다. 첫 GREEN에서는 기존 migration 전체 목록이 V14에서 끝나는 기대값 때문에 실패했다. 신규 V15 추가가 근거이므로 기대값에15를 추가했고 이전 파일의 checksum 검증은 유지했다. 최종 index·migration·schema·window14개 테스트가 통과했다. 실패한 실행은 통과로 합치지 않았다.
