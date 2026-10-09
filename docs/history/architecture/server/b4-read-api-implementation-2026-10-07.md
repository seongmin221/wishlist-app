# B4 상품 목록·홈 조회 구현 이력

> 2026-10-07 구현·전체 회귀 완료 / 2026-10-09 리뷰 보완: 실제 DB 재검증 대기

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

HOME-01은 MATERIALIZED classified의 공통 판정에서 FILTER count와 group별 row_number 미리보기 key를 한 번에 읽는다. 카드 projection은 최대12개 key를 한 batch로 조회하고, 최근 ACTIVE 목적3개와 B3 후보4개를 같은 repeatable-read connection에서 읽는다. latch로 첫 SELECT 후 다른 connection의 상품/purpose 변경 commit을 재현해 이전 응답 snapshot 유지와 다음 응답 갱신을 확인했다. 홈·B3 관련13개 테스트가 통과했다.

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

## Task 9 — 독립 리뷰와 검증 경계

구현에 참여하지 않은 새 context의 reviewer(gpt-6-astra)가 `ed1eef9..4a85bb3` 전체 변경을 read-only로 spec/plan과 대조했다. Review Focus5개·두 실행 판단·코드·테스트·실측 기록을 검토한 결과 Critical0/Important0/Minor0이었다. reviewer는 테스트를 실행하지 않았으며 diff check만 직접 수행했다. 재현/수정이 필요한 지적은 없었다. 이는 root가 별도로 수행한 전체 테스트 결과와 구분한다.

reviewer가 이번 결함으로 판단하지 않은 경계도 검토했다.

| 경계 | 유지/후속 판단과 영향 |
| --- | --- |
| unsigned cursor의 유효 위치 변경 | 승인된 위치 힌트 계약이며 SQL owner/scope가 권한을 제한한다. 위치 불변 요구가 추가되면 서명과 protocol 변경이 필요하다. |
| 요청 사이 변경 후 오래된 page cursor의 빈 window | 당시 빈 page 양쪽 cursor=null을 허용했으나 반대 방향 생존 항목을 놓치는 결함이었다. 2026-10-09 리뷰에서 이를 바로잡아 inclusive 복귀 cursor를 추가했다. |
| B8 삭제 영향·참조 해제 | 현재 삭제 mutation은 없다. 후속 구현에서 표시 count를 재사용하면 이름 없는 영향 상품을 누락한다. |
| B10 ARCHIVED 목적 연결 상품 | 현재 archive mutation은 없다. 후속 predicate를 갱신하지 않으면 두 목적 범위 모두에서 상품이 빠질 수 있다. |
| brand/price/metadataCheckedAt null | B5 추출/저장 경계를 유지한다. B4만으로 이 표시 데이터를 제공하지 않는다. |
| HOME count/summary 전체 scan | 정확한 count와 공통 판정의 관측 비용을 기록했다. 큰 owner에서는 지연이 상품 수에 비례하므로 별도 실측 최적화가 필요할 수 있다. |
| 운영 중 V15 index 생성 | 당시 일반 CREATE INDEX였으며 production online rollout은 검증하지 않았다. 추가 리뷰 후 원본 V15 checksum을 유지하고 V16의 concurrent 복구로 분리했다. V15 최초 적용 잠금과 실제 PostgreSQL 재검증은 아래 상태를 따른다. |

두 실행 판단도 검토했다. 공백 category는 현재 FK로 저장할 수 없어 유효 행 parity와 derived-expression 테스트를 나눴고, 후속 FK 완화가 있어도 표현 parity가 남는다. 미구현 GET의405는 기존 POST와 경로가 같아서 발생하며, 최종401/200/400 표현 검증은 별도로 통과했다.

## 최종 검증

2026-10-07 이 worktree에서 JDK17·Podman 설정으로 `./gradlew test --rerun-tasks`를 완료했다. [유효 실행 명령](../../../architecture/server/local-test-environment.md#전체-테스트-실행)의 환경을 사용했고 exit0, `BUILD SUCCESSFUL in 6m 4s`였다. JUnit 결과는 **tests321·통과320·failures0·errors0·RealUrlPilot skip1**이다. B1~B3·Worker·category·목적과 신규 목록/home 회귀를 포함했다. 중단/실패 실행을 최종 통과 수에 합치지 않았다. 실제 외부 URL pilot과 production 검증은 수행하지 않았다.

변경 문서의 상대 링크·JSON 예시와 diff check를 검증했다. V1~V14 파일과 client/·design/handoff/는 변경하지 않았다. 확정 제품 문서·계약·inventory·implementation order·INDEX와 계획 checkbox를 구현 결과에 맞췄다. 코드 수정 없는 독립 리뷰 이후에는 전체 suite를 이유 없이 반복하지 않았다. 로컬 의미별 커밋으로 마무리했다.

## 2026-10-09 리뷰 보완

사용자 리뷰의 빈 페이지 양방향 복귀 결함을 JDBC component 테스트로 먼저 재현했다. 오래된 방향/새로운 방향의 빈 페이지와 첫 페이지 불필요한 EXISTS 테스트 3개가 기존 코드에서 실패했다. 빈 page에서도 반대 방향 생존 항목을 포함하는 복귀 cursor를 제공하고, 경계 없는 최신 첫 페이지의 반대 EXISTS를 생략했다. 앞선 Task9의 빈 page 허용 판단은 이 결함을 놓쳤으므로 유지하지 않는다.

HOME-02는 owner ACTIVE CASE를 materialized 결과로 한 번 계산하여 정확한 totalCount·page/anchor·양방향 존재·빈 page 복구에 공유하는 통합 SQL로 바꿨다. 카드 batch 조회는 같은 snapshot에서 이어진다. exact count의 전체 ACTIVE 판정 비용은 남으며 stale cache나 count 생략은 도입하지 않았다. 기존 Task8 표는 **수정 전 개별 쿼리**의 관측값이다. 새 통합 page/anchor SQL의 동일 fixture EXPLAIN을 테스트에 추가했지만 아직 실행하지 못했으므로 성능 개선 수치를 주장하지 않는다.

기존 PREVIOUS token이 owner digest를 두 번 계산하는 실패를 확인한 뒤 token을 한 번 decode하고 방향을 읽도록 고쳤다. 응답 owner context는 100개 카드와 두 페이지 cursor에서 digest를 한 번 공유한다. B3/B4 digest·microsecond 변환, 홈/PUR 목적 요약 mapper, category/read의 순서 기반 SQL parameter bind를 공통화했다. 유효 기존 token의 wire 형태를 유지한다.

첫 리뷰에서 V15 변경을 시도했으나 추가 리뷰와 사용자 결정에 따라 원본을 복원했다. concurrent 복구는 후속 V16으로 분리하고 공통 Flyway session lock 설정을 적용했다. 세부 근거·중단 복구는 [QA-SRV-014](../../../learning/server/q-and-a/QA-SRV-014-concurrent-read-index-migration.md)에 남겼다. specs/INDEX도 추가했다.

소켓이 필요 없는 직접 Kotlin/JUnit 검증으로 main/test 전체 소스 컴파일과 확장 단위/component 회귀 **83개 통과·실패0·skip0**를 확인했다. 확장 첫 실행은 저장소 루트 cwd에서 taxonomy 상대 경로를 찾지 못해82 통과·1 실패였고, server cwd로 바로잡아 전체83개를 재실행했다. 실패 실행은 통과로 합치지 않았다. Gradle는 공용 캐시 lock 쓰기와 로컬 socket 생성 제한으로 실행하지 못했다. PostgreSQL 빈 page·정확한 count·CASE 1회·통합 EXPLAIN·V15 migration 테스트는 추가/갱신했지만 아직 실행하지 않았다. 위 2026-10-07의320 통과·1 skip은 수정 전 결과이며 이번 변경의 전체 회귀 결과로 재사용하지 않는다.

별도 read-only reviewer는 현재 production diff의 SQL parameter 순서·page/anchor·inclusive cursor·mapper·migration 설정을 정적으로 검토하여 구체적 결함을 찾지 않았다. 계약 문서의 기존 universal limit+1/EXISTS 설명을 갱신하라는 지적은 반영했다. 이 리뷰는 실제 PostgreSQL 실행 검증을 대신하지 않는다.

## 추가 리뷰 보완

공유 DB 적용 여부는 확인되지 않았고 사용자가 **원본 V15 보존**을 선택했다. ed496fc 바이트와 SHA-256 일치를 검사하여 기존 checksum을 보존했고 임시 V15 비트랜잭션 설정을 제거했다. 원본 V15는 transactional이므로 부분 DDL commit 시나리오를 일반화하지 않는다. V14 이하 DB의 최초 V15 CREATE INDEX 쓰기 잠금은 남는다.

2차 보완 당시 V16 Java migration은 비트랜잭션·고정 revision checksum을 사용했다. 이 방식의 변경 감지와 복구 경계는 아래 3차 보완에서 교정했다. 정상 인덱스는 유지하고, 대상 table과 validity/정의를 확인하여 INVALID만 정리한 뒤 IF NOT EXISTS/IF EXISTS를 붙인 concurrent DDL을 실행한다. 잘못된 table/정상 정의는 변경하지 않고 중단하며 실패 이력 repair는 자동 실행하지 않는다. 두 인덱스 완료 후 세 번째 실패·재실행, foreign table 거절, varchar의 PostgreSQL text cast, 잘못된 ACTIVE literal/predicate를 component 테스트로 확인했다. 첫 varchar 표현은 잘못된 정의로 거절됐으므로 RED 확인 후 실제 predicate를 별도로 검증하도록 수정했다.

Flyway lock·migration 위치는 공통 flyway.conf로 모으고 application/test의 migrate·validate를 DatabaseFactory.migrationConfiguration으로 통일했다. CLI도 같은 설정과 Java/Kotlin migration classpath를 읽어야 한다. migration 목록 기대값15→16은 후속 migration 추가가 근거이며 V1~V15 원본 파일은 유지한다. 실제 V15→V16 discovery/validate와 INVALID 인덱스 복구 fixture는 추가했지만 PostgreSQL 실행은 아직 하지 못했다.

ITEM-02와 HOME-02의 page/anchor/fallback/inclusive/정렬은 WishlistWindowReader 하나로 통합했다. count/window SQL 한 번과 상세 batch 한 번으로 구성하고 custom/purpose 자원 유효성 SELECT만 별도로 둔다. 미사용 repository Action 쿼리를 제거하고 EXPLAIN 테스트를 실제 service가 실행한 reader SQL capture로 바꿨다. HOME-01/02는 같은 action/group CTE를 생성하며 별도 action materialization으로 CASE 중복 계산을 막는다. 일반 목록 eligible은 NOT MATERIALIZED로 index keyset 경계를 밀어 넣을 수 있게 한다. 이 두 materialization 문제는 독립 정적 리뷰가 지적해 보완했으며 실제 plan/성능 재현은 아직 완료하지 않았다.

2차 보완에서 목적 summary entry 조립·SQL bind를 공유하고 홈 projection의 누락 row를 건너뛰게 했다. 누락 처리·named bind·decoder 범위는 아래 3차 보완에서 교정했다. Category SQL은 내부 named marker를 조합해 subquery 위치가 바뀌어도 값이 그 marker를 따라 bind되게 했다. B3 목적 encode에 새 범위 require가 생기지 않도록 기존 인코딩 동작을 복구했으며 decoder 범위와 B4 encode 검증은 유지한다. 목적 비정상 clock 인코딩과 첫 page의3회 왕복은 RED로 재현한 뒤 수정했다. 요청당 repository/reader 재생성과 중복 availability enum·미사용 owner overload도 제거했다.

최신 직접 Kotlin main/test 전체 소스 컴파일은 exit0이다. 29개 단위/component 클래스의 **91개 통과·실패0·skip0**를 확인했다. 앞선83개는 첫 리뷰 단계 결과다. 별도 reviewer의 마지막 정적 검토에서는 추가 결함을 찾지 않았다. 실제 PostgreSQL 전체 회귀·통합 EXPLAIN·운영 CLI/migration 검증은 실행 제한으로 대기하며 수정 전320 통과·1 skip으로 대체하지 않는다.

최종 전체 Gradle 재시도는 FileLockContentionHandler의 java.net.SocketException: Operation not permitted로 build 시작 전 exit1이었다. DB 테스트가 실행된 결과로 기록하지 않는다. 변경 문서의 상대 링크·diff check와 기존 migration 파일 변경 없음은 직접 확인했다.


## 3차 리뷰 보완

HOME-01 누락 projection과 목적 cursor의 encode/decode 불일치를 우선 수정했다. 기존 테스트는 잘못된 성공 응답·decode 실패를 기대하고 있어 기대값부터 교정했다. 두 테스트의 RED에서 각각 count=1/previews=[] 성공과 pre-1970 cursor decode=null을 확인한 뒤 production을 고쳤다. HOME-01은 같은 snapshot의 preview key에 상세 row가 없으면 ID를 포함한 invariant 오류로 실패한다. 최대4개 preview로 전체 count를 다시 계산할 수 없으므로 부분 count 보정은 사용하지 않았다.

목적 cursor는 PostgreSQL 최소 timestamp를 Unix microsecond로 변환한 값 이상·signed Long 범위를 양쪽에 적용했다. pre-1970/year10000과 넓은 양수 위치의 왕복을 확인했다. 독립 reviewer가 SQL 허용 범위 아래의 음수와 기존 PurposeRoutesTest의 year9999 거절 기대값을 지적했다. 극단 음수 decode를 RED로 확인해 SQL bind 전에 거절하고, route 회귀는 PostgreSQL 밖의 음수/Long overflow=400·허용된 양수 위치=200으로 바꿨다. B4 상품 cursor의 기존 1970~9999 범위는 해당 codec에 남겼다. 기존 테스트 기대값 변경은 저장 가능한 목적 위치의 왕복과 DB 오류 방지가 근거다.

V16 고정 checksum과 잘못된 정상 인덱스 거절 테스트도 RED를 확인했다. checksum은 Gradle이 패키징한 V16·전용 helper 원본 소스의 CRC32로 교체했다. 동일 table의 INVALID/다른 정의는 concurrent drop/create로 복구하고, 다른 table은 거절한다. 원본 V15 바이트는 계속 유지했다. API/Worker Main은 조사 시점부터 migrate를 호출하지 않았다. 별도 배포 `runDatabaseMigrations` job을 추가해 V16 실패만 현재 resolved artifact·type·description·양쪽 checksum까지 확인한 뒤 repair/retry한다. 독립 reviewer가 누락된 V16도 같은 실패 코드가 나온다고 지적해 info state=FAILED와 실제 checksum 일치를 추가했다. 다른 버전/checksum/누락 오류는 자동 repair하지 않는다. 운영 경계는 [QA-SRV-014](../../../learning/server/q-and-a/QA-SRV-014-concurrent-read-index-migration.md)에 정리했다.

requiredAction/group은 같은 조건 생성기와 policy homeGroupFor를 공유하고 홈은 group을 직접 계산하는 MATERIALIZED classified 하나만 저장한다. eligible은 NOT MATERIALIZED로 두었다. 실제 classified와 policy를 전체 유효 fixture에서 직접 비교하는 PostgreSQL 회귀를 확장했다. 조회 bind는 순서대로 전달하는 List/bindParameters로 통일하고, 물음표를 임의 치환하던 SqlBindings와 그 구현 전용 테스트를 제거했다. 카테고리 count SQL의 기존 bind 순서가 맞았다는 점도 확인했다. 첫 페이지 has_previous는 Boolean CASE bind로 EXISTS를 생략하며 그 부분의 SQL 문자열 분기를 제거했다. 위치 설정은 flyway.conf 한 곳만 따른다.

직접 Kotlin2.3.21/JDK17의 **main/test 전체 소스 컴파일 exit0**, 29개 단위/component 클래스의 **95 통과·실패0·skip0**를 확인했다. 마이그레이션 checksum 테스트에는 Gradle processResources와 동일한 두 실행 소스 resource를 준비해 사용했다. Gradle packaging 자체·실제 PostgreSQL count/window/parity/EXPLAIN·V15→V16 upgrade 및 failed 이력 재시도는 아직 실행하지 못했다. 앞선91개와 이번95개는 서로 다른 단계이며 합산하지 않는다.

[전체 Gradle 재시도 명령](../../../architecture/server/local-test-environment.md#제한-환경의-b4-전체-회귀-시도)은 build 시작 전에 FileLockContentionHandler의 `SocketException: Operation not permitted`로 exit1이었다. 테스트 실행 결과가 아니다. 별도 reviewer는 최종 production 변경과 cached Flyway11.20/pgjdbc bytecode를 읽어 구체적 추가 결함을 찾지 않았으며 테스트는 실행하지 않았다. docs/spec/계약/학습/INDEX를 현재 구현에 맞췄다. Git 공용 metadata 쓰기와 네트워크 제한으로 보완 커밋·push/PR은 완료하지 않았다.


## 권한 전환 후 전체 회귀와 배포 job 검증

2026-10-09 제한 해제 환경에서 [표준 Podman 명령](../../../architecture/server/local-test-environment.md#전체-테스트-실행)으로 `./gradlew test --rerun-tasks`를 완료했다. exit0·BUILD SUCCESSFUL in 7m, **tests345·통과344·failures0·errors0·RealUrlPilot skip1**이다. 앞선 소켓 오류 실행과 직접95개 결과를 이 전체 결과에 합산하지 않았다. B1~B3·Worker, owner/filter cursor 격리, 같은 시각 정렬, 빈 page/anchor 복구, 홈 count/preview/action 일치와 목적 count, 실제 SQL/policy parity를 포함한다. V15→V16 discovery/validate·INVALID 복구·failed V16 재시도와 Gradle checksum 소스 packaging도 실제 PostgreSQL에서 통과했다.

별도 일회성 PostgreSQL16-alpine 컨테이너에서 `./gradlew runDatabaseMigrations`를 직접 실행했다. 빈 DB의 V1~V16 적용 후, 임시 DB에만 다른 table의 동명 인덱스를 만들어 V16 실패를 유도했다. 해당 실행의 실패·failed 이력·foreign table 보존을 확인한 뒤 충돌 인덱스를 정리하고 같은 명령으로 repair/retry했다. 이후 success16개·failed0개·정상 인덱스를 확인했고 마지막 재실행에서는 인덱스 OID가 유지됐다. 예상 실패 호출을 성공한 Gradle 실행으로 기록하지 않는다. 이 검증용 컨테이너는 정리했으며 다른 기존 컨테이너는 수정하지 않았다. production Neon rollout과 실제 URL pilot은 수행하지 않았다.

### 공통 reader 실제 EXPLAIN

WishlistReadIndexTest가 현재 service의 SQL/bind를 capture하여 migration 전 V14와 원본 V15+V16 적용 후에 같은 query/fixture를 측정했다. PostgreSQL16.15/aarch64, 두 owner20,000행·owner별10,000행, 각 홈 그룹100개·NONE9700개다. 아래 ms는 해당 SQL 실행 시간이고 카드 projection/HTTP 전체 지연이 아니다. scan은 wishlist_items 노드의 Actual Rows+Rows Removed by Filter에 loops를 곱한 합계라 같은 행의 반복 읽기도 포함한다.

| query | V14 ms / scan | V15+V16 ms / scan |
| --- | --- | --- |
| public-page | 3.352 / 10081 | 2.139 / 5121 |
| custom-page | 3.019 / 10082 | 1.827 / 5123 |
| purpose-page | 0.796 / 5121 | 0.886 / 5121 |
| unassigned-page | 2.500 / 20083 | 2.018 / 20161 |
| home-ANALYSIS_IN_PROGRESS-page | 7.140 / 10000 | 7.777 / 20000 |
| home-INFORMATION_COMPLETION-page | 7.233 / 10000 | 7.750 / 20000 |
| home-CLASSIFICATION_REVIEW-page | 7.062 / 10000 | 8.278 / 20000 |
| home-summary | 7.758 / 10000 | 8.479 / 20000 |

모든 캡처 query의 temp read/write는0이었다. HOME-01/02의 실제 plan은 wishlist_items base 노드1개로 분류함을 검사했다. 정렬 인덱스는 category page 스캔을 줄였으나 홈은 V14의 owner10,000행 index scan에서 전체20,000행 seq scan으로 바뀌고 실행 시간이 증가했다. 목적 미지정도 일반 page는 개선됐지만 previous는 scan30102→40102·2.882→3.380ms로 증가했다. exact totalCount와 group 필터의 비용은 남으며 인덱스가 모든 조회를 개선한다고 주장하지 않는다. 기존 Task8의 독립 query 실측과 이번 통합 SQL 관측을 구분한다.
