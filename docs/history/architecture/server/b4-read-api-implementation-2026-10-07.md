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
