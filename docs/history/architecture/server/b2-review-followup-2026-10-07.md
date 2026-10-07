# B2 후속 리뷰 보완

> 2026-10-07 · 보완·독립 리뷰·전체 검증 완료

기준은 B2 최초 구현 HEAD `1d2f8ec`이다. 당시 전체는 245개 중244 통과·실패/오류0·RealUrlPilot1 skip이었다.
아래 변경은 사용자의 추가 코드·문서 피드백을 대조한 결과다. 기존 구현 결과와 후속 검증을 구분한다.

## 동작 보완

1. custom 없는 owner도 전체 상품 metadata 2,400자 이후 공용 최소160자를 preflight한다. custom 유무에 따라 같은 긴 상품의 공용 분류 가능성이 달라지는 경로를 제거했다. 유료2,000/80 상한과 가격표는 유지한다.
2. 호출 전 stale snapshot 때문에 반환한 Partial placeholder를 확정/보류 또는 USER category에 그대로 반영하지 않는다. 해당 AI assignment를 건너뛰고 기존 category 기준으로 READY/PARTIAL을 계산한다. generation과 연결·review를 보존하며 독립적 extraction 실패의 기존 처리도 유지한다.
3. stale replacement 예산 소진은 job FAILED·item FAILED_RETRYABLE로 기록한다. 실제 replacement를 만들 때만 기존 job CANCELLED다. claim/reconciler의 소진 의미와 맞췄다.
4. Unicode 입력 범위를 임의로 확대하지 않았다. U+2028/2029 내부 구분 문자, U+3164/U+2800 filler, LRM/RLM/ALM은 현재 허용하는 범위로 계약과 테스트에 명시했다. LF/CRLF 설명 허용, 금지 control/surrogate/bidi 범위, 정상 결합 문자 보존은 유지한다.
5. V12에 public category FK를 NOT VALID로 추가했다. 이미 저장된 unknown legacy 참조는 보존하면서 새/변경된 비공용 참조는 DB에서 차단한다. rollout 시 역사 참조를 점검·수선하고 VALIDATE하는 절차를 영속성 문서에 남겼다. V1~V11은 다시 쓰지 않았다.

새 회귀 18개 실행에서 public-only fallback, 보호된 상품의 stale Partial, 예산 소진 stage와 공용 FK 방어의 네 항목이 실제 실패했다. 이를 보완했다. 중간 리팩터링 실행의 컴파일 오류도 통과로 기록하지 않는다.

## 구조 정리

CandidateSnapshotCodec으로 JSON encode/decode를 모았다. StoredCandidates를 한 번 읽고 해석한 결과를 재사용 검증에 전달하며 최종 finish는 별도 transaction에서 최신 상태를 확인한다. CategoryRef가 알려진 공용 ID와 정규 UUID custom을 구분한다. v2에서도 알 수 없는 비UUID 후보를 거절한다. 기존 테스트용 가상 category ID는 실제 공용 ID로 바꾸어 검증을 약화하지 않았다.

AI 공급은 connection/claim을 받는 CategoryCandidateSupply 하나로 통일했다. 테스트도 같은 인터페이스를 쓰며 두 nullable 전략·테스트 전용 생성자를 제거했다. HTTP parser와 category service는 CategoryChange 하나를 공유한다. 생성·replay의 개수는 COUNT(*)로 읽고 taxonomy v1은 lazy catalog를 공유한다. 긴 service query는 이름 있는 helper로 분리했고 핵심 guard 조건과 주변 코드를 정리했다.

## 문서 정리

- [API 계약](../../../architecture/server/category-management-api.md): CAT-01~04, 입력/오류, 상품 응답 영향, 요청·응답 JSON과 429 헤더 예시.
- [AI 후보 설계](../../../architecture/server/category-ai-candidates.md): 공급·snapshot·입력 안전성·상황별 stale 표·custom 유무별 token 단계.
- [영속성](../../../architecture/server/wishlist-state-persistence.md): V11/V12 스키마·owner 잠금·FK rollout.

architecture에는 현재 규칙을 두고 당시 사용자 확인·미정 초안 수명은 제품 결정 기록으로 연결했다. 검증 계획 나열은 실제 검증 이력 링크로 바꿨다. inventory/order/INDEX는 후속 상태와 맞춘다.

## 검증과 리뷰

후속 targeted 78개는 failures=0, errors=0, skipped=0으로 통과했다. 두 독립 읽기 리뷰에서 추가 Critical/Important는 없었다. 문서의 count 표현과 trailing whitespace를 보완했다. 사용자 지정 전체 --rerun-tasks를 단독 실행해 BUILD SUCCESSFUL, exit0, 4분23초를 확인했다. XML은 tests=252, failures=0, errors=0, skipped=1로 **251 통과·RealUrlPilot1 skip**이다. 이전 245개 결과와 구분한다. B1/Worker와 V10/V11→V12 upgrade 회귀를 포함했고 git diff --check, 문서 JSON 예시·링크 및 V1~V11 무변경을 확인했다. baseline·이전 실패·skip을 별도로 구분한다. 의미별 한글 로컬 커밋만 남기며 push/PR/merge와 다른 worktree 변경은 하지 않는다.


전체 실행 명령(server/):

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home DOCKER_HOST=unix:///Users/user/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 RUN_REAL_URL_PILOT=0 ./gradlew test --rerun-tasks
```
