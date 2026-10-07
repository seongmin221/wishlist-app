# B3 목적 기본 관리 구현과 리뷰

> 2026-10-07 · B3 수신·정책 확인·구현·독립 리뷰·전체 검증 기록

## 수신과 baseline

develop `1c6d949`(PR #9 B2 병합)에서 만든 독립 Orca worktree `seongmin221/server-b3-purpose-management`에서 시작했다. 시작 시 clean을 확인했다.
이전 B1/B2 공간과 HANDOFF 파일은 사용하지 않았다. 이 공간에서 직접 실행한 baseline 전체 `--rerun-tasks`는 tests=252, failures=0, errors=0, skipped=1(RealUrlPilot)이다.

## 정책 확인과 설계 리뷰

제품 문서에 없던 정책을 구현 전에 사용자와 확정했다. 대상은 입력 제한, ACTIVE 30개·60초 10건·영구 receipt, 활동순, AI 근거(목적 10개·상품명 2개), 편집 후 재판단 없음, 확정·보류 상품의 빈 목적 보호, 후보 수, 기본 아이콘이다.
설계 리뷰는 두 차례 받았다. 그 결과 입력 상한을 2,500으로 올리고 예산을 비례 상향했으며, 목적 근거를 먼저 줄이는 T0~T7 단계, 상품명 20자, snapshot v3 순서 보존, 판단/판단 없음 구분을 확정했다.
결정은 [B3 제품 결정](../../product-planning/mvp/decisions/b3-purpose-api-policy-2026-10-07.md)과 [설계](../../../superpowers/specs/2026-10-07-b3-purpose-management-design.md)에 있다.
상한 질문의 답은 [QA-AI-008](../../../learning/ai/q-and-a/QA-AI-008-input-cap-and-tier-order.md)에 남겼다.

## 구현

[구현 계획](../../../superpowers/plans/2026-10-07-b3-purpose-management.md) Task 1~7을 의미별 커밋으로 진행했다.

1. 공용 `UserTextRules`, 목적 색 6개·아이콘 8개 stable key, 이름·설명 제한.
2. V13: 목적 테이블, legacy 목적 문자열을 `legacy_purpose_id`에 보존, `(owner_id,purpose_id)` FK VALID, receipt 목적 대상, `pending_purpose_judged`. snapshot v3와 AI 목적 판단/판단 없음 반영 보호를 함께 넣었다.
3. PUR-02/03 서비스. receipt·rate limit·transaction helper를 category와 공유한다.
4. PUR-01/04 서비스. 활동순 keyset, 미리보기, archive 입구 수, expectedVersion 편집과 no-op.
5. HTTP route·parser·cursor·오류 계약과 상품 응답의 목적 이름·색·아이콘.
6. 입력 상한 2,500, 일·월 ceiling 721,000·7,210,000 micro USD, V14로 기존 기본 window ceiling 갱신.
7. 목적 후보 공급, alias `P01`~`P10`·짧은 key·T0~T7 단계, 단계 로그.

계약은 [목적 API](../../../architecture/server/purpose-management-api.md), AI 내부는 [AI 목적 후보](../../../architecture/server/purpose-ai-candidates.md), DB 경계는 [상태 영속성](../../../architecture/server/wishlist-state-persistence.md#b3-목적-schema와-잠금)에 있다.

**구현 중 판단(ruling).** 모두 테스트만 바꿨고 production 동작에는 영향이 없다.
- route 테스트의 상수 이름이 Ktor `HttpRequestBuilder.body`와 겹쳐 `createBody`로 바꿨다.
- 예산 fixture의 한도 1,000은 496 예약 두 건이 들어가는 값이었다. 596 두 건이 들어가도록 1,200으로 올렸다.
- B2 gateway 테스트의 기대값을 v2 snapshot의 `purposeJudged=false`로 바꿨다.
- V12 upgrade 테스트는 legacy row를 현재 서비스 대신 SQL로 넣는다. 현재 서비스는 최신 schema를 전제하기 때문이다.

**바뀐 기존 동작.** CONFIRMED/DEFERRED 상품의 빈 목적은 AI가 채우지 않는다. 확정 표현이 없는 문자열 목적 결과는 "판단 없음"이며 기존 연결을 유지한다. 이에 맞춰 B0/B2 회귀 테스트(목적만 AI인 검토, 확정 연결 보호)를 새 규칙으로 바꿨다.

## 독립 리뷰와 보완

opus 독립 리뷰의 판정은 "With fixes"였다(Critical 0, Important 1, Minor 6). 효과 기준으로 다시 분류해 다음 4건을 보완했다. 각 수정은 재현 테스트의 실패를 먼저 확인했다.

- **위조 cursor.** owner tag는 맞고 timestamp만 극단값인 cursor가 400이 아니라 **200으로 통과**했다. 리뷰어는 500을 예상했다. 이제 0~9999년 범위 밖이면 `INVALID_PURPOSE_CURSOR`다.
- **보지 못한 연결의 해제.** "목적 미지정" 판단이, 모델이 보지 못했거나 그 뒤 이름이 바뀐 현재 AI 연결을 해제하던 문제를 막았다. 현재 연결이 없거나 snapshot에 원문 그대로 있을 때만 판단으로 본다.
- **단계 로그 누락.** 유료 호출을 보낸 뒤의 재시도·실패 응답에도 선택 단계를 붙여 로그한다.
- **문서 최신화.** runtime·category 문서의 2,000 문구와 spec의 V14·로그·판단 문구를 고쳤다.

**보류한 Minor.** `Assigned.purposeJudged` 기본값, receipt CHECK와 operation의 결합, ASCII `btrim`, `CategoryChange` 재사용, 공용-only 요청의 `"purposes":[]`.

**rollout 주의.** V13/V14 적용 전에 구 API/Worker를 멈춰야 한다(B0 drain과 같음). 구 버전이 uuid 컬럼에 문자열 목적을 쓰거나, 600,000 ceiling으로 새 window를 만들면 실패하거나 해당 window가 Exceeded가 된다.

## 전체 검증

실행 명령(server/):

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home DOCKER_HOST=unix:///Users/user/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 RUN_REAL_URL_PILOT=0 ./gradlew test --rerun-tasks
```

이 기기에는 colima socket이 없어 Testcontainers가 `/var/run/docker.sock`(podman)으로 연결했다.

| 실행 | 결과 |
| --- | --- |
| 리뷰 전 | BUILD SUCCESSFUL, tests=289, failures=0, errors=0, skipped=1 |
| 보완 후 최종 | BUILD SUCCESSFUL, 5분25초, tests=292, failures=0, errors=0, skipped=1 → **291 통과·RealUrlPilot 1 skip** |

실행하지 않은 범위: 실제 OpenAI 호출, production 배포, RealUrlPilot(opt-in), B4~B11. push·PR·병합은 하지 않았다.
