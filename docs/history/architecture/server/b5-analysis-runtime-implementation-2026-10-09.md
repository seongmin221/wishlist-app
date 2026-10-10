# B5 비동기 분석·운영 복구 구현 이력

> 2026-10-09 착수 · 2026-10-10 구현·독립 리뷰 반영·전체 회귀 완료 · 브랜치 `server/b5-analysis-runtime-recovery`

## 범위와 근거

[B5 구현 순서](../../../architecture/server/mvp-api-implementation-order.md#b5--비동기-분석과-운영-복구)의 WORK-01 → WORK-02 → OPS-01을 구현했다. 정책은 [B5 제품·운영 결정](../../product-planning/mvp/decisions/b5-analysis-runtime-policy-2026-10-09.md) 6건, 설계는 [spec](../../../superpowers/specs/2026-10-09-b5-analysis-runtime-recovery-design.md), 실행은 [계획](../../../superpowers/plans/2026-10-09-b5-analysis-runtime-recovery.md) 12 Task를 따랐다. 착수 대조는 [착수 기록](b5-runtime-recovery-preparation-2026-10-09.md)에 있다. 클라우드 IAM·Scheduler 실호출·배포 egress firewall은 B11이다.

## 구현 요약

| 영역 | 결과 |
| --- | --- |
| V17 | 상품 brand·가격(numeric 19,4)·통화·판매처·확인 시각, job 임시 metadata, `recovery_seq`·`recovery_check_at`, outbox `not_before`, 복구 index. B11 이전이라 일반 `create index` |
| 추출 | JSON-LD/OG 기반 brand·단일 가격·ISO 통화·판매처, 같은 등록 도메인 canonical·tracking query 제거 |
| 병합 | 성공 `pending ?: existing`·실패 `existing ?: pending`·BRAND override 보호. 페이지를 읽은 반영은 확인 시각을 기록하고 가격 쌍을 교체 |
| 예산 | generation 합산 3회·30분 단일 함수, 소진은 모든 경로가 `failExhausted`(읽은 metadata 반영). NeedsBrowser도 예산 확인 |
| Worker 응답 | retry outbox 저장 시 204, `not_before` backoff 10초부터 2배·최대 600초. durable 기록 없는 RETRY만 503 |
| URL | DNS 실패(`BoundedResolver` 시간 초과·포화 포함)는 일반 Retryable·browser PARTIAL, 안전하지 않은 주소는 `BLOCKED_ADDRESS` Terminal |
| browser | browser-worker 역할, render별 loopback pinning proxy, Chromium proxy 강제·QUIC·비proxy WebRTC·service worker·다운로드 차단 |
| outbox | backlog 발행의 이벤트별 실패 격리·시간 상한, scheduleTime 전달, TaskGateway `status`(getTask 5초) |
| 복구 | RUNNING·PENDING 순환 검사(`recovery_check_at`), PENDING 5분 정체·ALIVE 5분·조회 실패 1분, 재예약 누적 3회 상한 |
| maintenance | maintenance 역할 `/internal/maintenance/run`, 발행 30초·복구 50초 공유·budget 항상 실행, 단계 실패 시 500 + report |

## 계획 대비 결정(Rulings)

- 기존 upgrade 테스트 snapshot과 B4 V14 기준 측정은 V17 컬럼을 제외하거나 측정 동안만 임시 추가했다. 기존 데이터 불변 확인은 유지된다.
- 선언 canonical은 엄격한 URI 문법을 통과해야 채택한다. 느슨한 resolve가 임의 문자열을 같은 사이트 상대 경로로 받아들였기 때문이다.
- `updated_at`·`metadata_checked_at`·`classified_at`은 한 번의 DB 시계로 기록한다.
- 소진된 Worker Retryable도 `failExhausted`로 끝낸다. 응답의 `category.missingReason`만 null이 되고 requiredAction·공개 failure code는 같다.
- maintenance 역할 config는 Main 조립과 함께 Task 10에서 추가했다.
- proxy 연결 timeout은 고정 30초다. proxy thread에는 처리 deadline이 없고 navigation timeout과 render 종료가 실제 상한이다.
- 기존 `dispatchPending(limit): Int`는 새 `dispatchPending(limit, deadlineNanos)`에 위임하는 형태로 남겼다.
- 복구 순환은 잠김·예외 후보만 1분 미룬다. 재검증에서 상태가 바뀐 후보는 다음 실행에서 바로 다시 평가한다. 이에 따라 기존 "후보 1개 실패" 테스트의 기대를 1분 지연 후 재복구로 바꿨다.
- 105초 마감 뒤 중복 ACK 시나리오는 "미발행 최신 outbox가 있는 PENDING은 발견하지 않음" 테스트로 갈음했다. 리뷰어는 실제 재전달 경로를 직접 확인하지 않는다는 점에서 약하다고 평가했다. spec이 수용한 위험이다.

## 독립 리뷰와 반영

구현에 참여하지 않은 reviewer(Opus)가 `abae37d..231db35` 전체를 spec·계획·결정과 대조했다. Critical 0, Important 3, Minor 8이었다.

| 지적 | 처리 | 검증 |
| --- | --- | --- |
| JSON-LD `null`이 문자열 "null"로 저장 | 문자열 필드는 JSON 문자열만, 가격만 숫자 허용, 이름 있는 Product 우선 | `json null values are treated as missing…` RED→GREEN |
| 느린 queue 발행이 50초를 다 써 복구·예산 단계가 굶음 | 발행 30초 상한, budget 항상 실행 | `outbox publication gets its own share…` RED→GREEN |
| IPv6 ULA·변환 대역 미차단, proxy IPv6 literal 허용 | fc00::/7·2002::/16·64:ff9b::/96·IPv4 호환 차단, proxy literal 거부 | UrlSafetyPolicy·EgressProxy 테스트 RED→GREEN |
| (Minor→Important 재등급) render 종료가 다른 render 연결까지 닫음 | render별 proxy 생성·종료 | 실제 Chromium 2회 render·proxy 분리·종료 확인 |

수정하지 않고 남긴 Minor: proxy 기본 connect 실패 시 Socket 미종료, proxy slot 32와 Chromium 한도 일치, PENDING 예외 경로의 defer 재실패 시 단계 실패, reconcile의 deadline 미사용, V17 `wishlist_items` CHECK의 전체 스캔(B11 이전 운영 데이터 없음 전제), reconciler defer가 다른 transaction 뒤 최대 1분 지연을 남길 수 있음, 공유 resolver의 동시 lookup 20건 초과 시 실패.

## PR #17 코드리뷰 반영 (2026-10-10)

PR 단계에서 별도 reviewer subagent가 `origin/develop...HEAD`를 spec과 대조했다. High 1, Medium 2, Low 3이었다. 동시성·잠금 순서 쪽에서는 문제를 찾지 못했다.

| 지적 | 처리 | 검증 |
| --- | --- | --- |
| (High) DNS가 돌려준 IPv4-mapped AAAA(`::ffff:169.254.169.254`)가 `Inet6Address`로 남아 차단을 우회 | mapped는 내장 IPv4로 재검사, SIIT·Teredo·64:ff9b:1::/48 차단 | `Inet6Address.getByAddress`로 만든 DNS 응답 테스트 |
| (Medium) Playwright 시작·launch 실패까지 navigation timeout으로 바뀌어 PARTIAL 확정(spec §5 위반) | navigation·내용 읽기 구간만 PARTIAL, 나머지는 전파해 Retryable | Playwright 생성 실패 주입 테스트 |
| (Medium) browser 공용 resolver 포화로 정상 host가 403·DNS 실패 | render 안 route·proxy 검증 결과 재사용, browser resolver 8·128, 시간 초과 조회를 queue에서 제거 | proxy 고정 재사용 테스트, 실제 Chromium 테스트 |
| (Low) reconcile 단계가 maintenance deadline 무시 | `reconcileExpired(deadlineNanos)` | 마감 후 후보 보류 테스트 |
| (Low) 평문 HTTP proxy 연결 재사용으로 다른 origin 요청이 첫 host로 전달 | 응답 hop-by-hop 헤더 제거 후 `Connection: close` | keep-alive upstream 테스트 |
| (Low) V17 CHECK 즉시 검증의 잠금 | 변경 없음. B11 이전 운영 데이터 없음 전제를 migration 주석에 이미 기록 | — |

proxy는 이제 연결마다 다시 해석하지 않고, 한 render 안에서 처음 검증한 주소로 고정한다. 기존 "연결마다 재검증해 rebinding 응답을 거부" 테스트는 "rebinding 응답을 아예 쓰지 않음"으로 바꿨다. 검증된 주소에만 연결한다는 보안 성질은 같다. 실제 backoff가 다음 maintenance 실행에 묶인다는 점은 [PENDING 복구 문서](../../../architecture/server/analysis-pending-recovery.md)에 적었다.

반영 후 `./gradlew test`: 439 tests, 실패·오류 0, skip 2(opt-in). `RUN_BROWSER_TESTS=1` `PlaywrightRealBrowserTest` 통과.

## 검증

명령은 [로컬 테스트 환경](../../../architecture/server/local-test-environment.md#전체-테스트-실행)의 Podman/JDK 설정으로 실행했다.

- 각 Task는 RED(신규 symbol compile 실패 또는 assertion 실패)를 확인한 뒤 GREEN을 기록했다. Task 11 전체 흐름은 선행 구현으로 즉시 통과해, 핵심 기대값 4곳을 일시 반전해 4/4 실패를 확인하고 되돌렸다.
- 리뷰 전 전체 회귀 `./gradlew test --rerun-tasks`: 432 tests, 430 통과, 실패·오류 0, skip 2.
- 리뷰 반영 후 전체 회귀 `./gradlew test --rerun-tasks`(`36bf9e0`): 435 tests, 433 통과, 실패·오류 0, skip 2. skip은 opt-in `RealUrlPilotTest`(RUN_REAL_URL_PILOT=0)와 `PlaywrightRealBrowserTest`이며 통과로 세지 않는다.
- `RUN_BROWSER_TESTS=1`로 `PlaywrightRealBrowserTest`를 별도 실행해 통과했다(로컬 chromium-1234, skip 0). 실제 Chromium이 proxy를 거쳐 렌더링하고 사설 subresource에 연결하지 않으며, render마다 proxy가 분리·종료됨을 확인했다.
- 실제 PostgreSQL(Testcontainers)에서 V16→V17 upgrade·CHECK, 동시 PENDING 복구 1건, 복구 대 claim·늦은 finish 경합, 잠긴 앞 batch 뒤 후보 진행, 전체 흐름을 검증했다.

## B11로 넘기는 항목

maintenance·Worker route의 OIDC invoker와 Scheduler 실호출, getTask IAM 권한, browser 컨테이너의 단일 workload와 네트워크 egress firewall, 역할별 instance 수를 곱한 DB 연결 한도, Scheduler 호출 timeout(maintenance 실행 상한 50초 + 발행 RPC 초과분)을 실제 배포에서 확인한다.
