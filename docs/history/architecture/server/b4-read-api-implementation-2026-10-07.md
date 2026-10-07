# B4 상품 목록·홈 조회 구현 이력

> 2026-10-07 · 제품 정책 확정·설계 spec 검토 전 · 제품 코드 구현 미착수

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

설계 피드백을 반영해 B3 형태의 구조/범위 검증 cursor로 단순화하고 신규 secret 제안을 제거했다. ITEM-02의 기존 anchor query와 requestedAnchorItemId를 유지하며 HOME-02 확장과 대체 항목 우선순위를 계약 비교표에 구분했다. 표시용 count와 B8 삭제 영향의 집계를 분리하고, 홈 SQL·index 교체 검토·Unicode 공백 상수·DB 유효 조합 parity·반대 방향 EXISTS를 구체화했다. 상세 설계와 근거는 spec에 모았다. 제품 정책은 확정됐지만 설계 spec 승인은 아직 받지 않았다. B4 제품 코드와 테스트는 미착수다.
