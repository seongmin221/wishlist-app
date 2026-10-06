# B0 서버 기반 구현과 검증 기록

> 2026-10-05 · `server/b0-foundation` · Task 1~9 · B1 인계

## 완료 범위

역사적 분기점은 제품 문서가 통합된 `4d31e3c`, Task 8까지 runtime 구현 HEAD는 `96786fb`다. Task 9는 전체 회귀·쓰기 감사·문서 정리다. main의 이후 client 변경은 이번 작업에 통합하지 않았다.

- 상품 상태·허용 행동의 순수 정책, 공통 DTO·가격 Decimal·공개 오류 계약.
- V8 상품 상태/출처와 V9 실행 token/lease/claimed version, owner 범위 상태 조회.
- 원자 claim, 중간 결과·후보 snapshot·AI 예약 보호, 최종 결과·사용자 값 보호, 만료 lease 복구.
- 요청의 blocking IO 경계, 역할별 bounded pool, client 재사용, 시작 실패/종료 자원 정리와 발행 gate.

37개 제품 API 중 생성 POST만 부분 구현돼 있고 36개 route는 후속 묶음이다. B0 완료가 제품 API 전체 완료를 뜻하지 않는다. 현재 생성 응답은 기존 문자열 mapper를 유지한다. 공개 GET 상세와 공통 mapper는 B1이다.

## 실제 검증

Docker 28.4.0(Colima), JDK 17.0.18, Gradle 9.7.1 환경에서 다음 명령을 실행했다.

```sh
cd server
JAVA_HOME=$(/usr/libexec/java_home -v 17) \
DOCKER_HOST=unix:///Users/user/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 \
RUN_REAL_URL_PILOT=0 ./gradlew test
```

결과: **BUILD SUCCESSFUL, 4분 21초 · 151개 중 150개 통과 · 실패/오류 0 · skip 1**. skip은 실제 외부 URL 8개를 호출하는 opt-in `RealUrlPilotTest`뿐이다. 실제 PostgreSQL migration·경합·budget 테스트는 환경 문제로 제외되지 않았다.

대표 실행 증거: DatabaseMigration 3, AnalysisClaimRepository 7, AnalysisJobReconciler 10, GeneralWorkerService 13, BrowserWorkerService 10, AiClassificationService 9, LlmBudgetService 6, WishlistItemPolicy 14, ApiError 10, DatabaseFactory 4, RuntimeResources 4개. 생성/replay·auth·health·Worker 204/503 회귀도 통과했다.

## 쓰기 경로 감사

대소문자 포함 item/job INSERT·UPDATE·DELETE와 pending/candidate 쓰기를 검색하고 각 transaction을 읽었다.

| 위치 | 확인한 보호/원자성 |
| --- | --- |
| CreateWishlistItemService | item/job/outbox 같은 transaction, 기존 owner/key 유지, commit과 connection 반환 후 동기 발행 |
| AnalysisClaimRepository | item→job 잠금, 현재 generation/관계/상태 재검증, DB 시각 lease, token 새 발급, 소진 시 현재 상품만 실패 |
| AnalysisPendingResultRepository | 같은 guard transaction에서 metadata/assignment/failure·source URL·후보 snapshot 접근; 최초 snapshot은 lease 재확인 |
| AnalysisWriteGuard | 명시적 transaction, owner/관계/generation/token/stage/lifecycle/manual/version/lease 검증 |
| AnalysisResultRepository | guard 뒤 item/job 반영; stale는 무변경 ACK; retry·fallback·최종 version 전이 원자적; USER 값과 명시적 목적 해제 보호 |
| AnalysisJobReconciler | 잠금 없는 발견 뒤 item→job SKIP LOCKED, 발견 identity/lease 재확인; 재예약 outbox 원자적; invalid는 job만 취소 |
| AI/budget | 예약은 claim guard와 window 잠금 후 재검증; 실제 발생 비용 정산은 상품 결과 저장과 독립 유지 |
| OutboxDispatcher | outbox 발행 상태만 변경; 취소 cleanup 후 예외 재전파; 종료 시 새 발행 admission 차단 |

processor/classifier/budget의 상품 쓰기 entry는 AnalysisClaim을 받는다. HTTP jobId/generation entry는 DB claim을 얻고 진행한다. 원격 gateway 자체는 상품 DB를 쓰지 않는다. updated_at 기반 복구와 Worker의 옛 최종 SQL은 남아 있지 않다.

## Migration과 legacy 적용 순서

`4d31e3c` 대비 V1~V7 파일 변경은 없다. 빈 DB와 V7 데이터 upgrade 테스트를 실제 PostgreSQL로 통과했다. V8은 UUID·owner·생성 key·URL·createdAt·version·기존 metadata·job/outbox·예산 데이터를 보존하고 현재 상태/출처를 확장한다. 목적 자원이 없는 legacy predicted purpose는 진단 값으로 남긴다. V9는 기존 RUNNING의 token/claimed version을 null, lease를 만료로 두어 옛 실행을 유효한 새 claim으로 인정하지 않는다.

운영 적용 시 다음 순서가 필요하다. **이번 작업에서는 운영 적용하지 않았다.**

1. queue dispatch와 새 작업 유입을 정지하고 구 일반/browser Worker의 실행을 drain한 뒤 중지한다. 구 revision으로 트래픽이 들어가지 않게 한다.
2. 복구 지점을 확보하고 전용 migration 계정으로 V8/V9를 별도 적용한다. 실패하면 앱 배포를 중단하고 queue를 정지 상태로 유지한다.
3. 보호 코드가 적용된 서비스만 배포한다. B5에서 준비할 browser/maintenance runtime도 같은 실행 계약을 사용해야 한다.
4. 새 코드로 만료 legacy 실행을 복구하고 smoke 검증 후 queue를 재개한다. lease·재예약·실패율·예산 정산을 관측한다.

구 Worker의 unguarded SQL은 migration만으로 막히지 않으므로 구/새 Worker 혼재를 허용하지 않는다. 새 schema와 구 코드를 섞는 단순 rollback 대신 복구 지점·호환성을 확인해야 한다.

## B1 구현 인계

구현 순서는 [전체 구현 순서](../../../architecture/server/mvp-api-implementation-order.md)의 B1을 따른다.

1. clientCreatedAt와 optional metadata 입력 규칙을 계약에 맞춰 정리한다.
2. 기존 `WishlistItemStateRepository`를 재사용해 owner+item ID로 전체 상세 projection(URL·metadata·시각 포함)을 읽는다. 일반 상세에서 DELETED는 404, 다른 owner도 404다. 생성 replay tombstone 정책은 별도로 보존한다.
3. 기존 정책·DTO·Decimal·ApiError·IO/pool을 사용해 상세 mapper와 GET을 먼저 구현한다. DTO를 중복 생성하지 않는다.
4. 생성/replay도 같은 mapper로 실제 상태와 metadata를 반환하게 한다. 현재 상수/null 문자열 응답을 교체한다.
5. CREATED 201+Location, replay 200+replay header, 같은 key/다른 URL 409, 다른 owner 404, 상태/수동완료/재지정/USER purpose null, job/outbox 중복 없음 회귀를 확인한다.

category/purpose 실자원·owner FK·후보 version은 B2/B3에서 연결한다. 아직 없는 정보를 임의 label/ID로 채우지 않는다. generic extraction/retry의 failureCode가 null인 기존 경로는 B1 공개 실패 mapper와 B5 실패 분류에서 안전한 코드 계약으로 보완한다.

## 후속 범위와 미실행 항목

- B1 공개 상세·공통 mapper·clientCreatedAt, B2/B3 실자원 CRUD/FK/후보 version.
- B5 전체 retry 3회·30분 합산과 browser/maintenance runtime·Scheduler/private 인증. B0는 기존 lane별 한도를 유지한다.
- B6 media, B7 변경/재분석, B8 삭제 영향, B9 목록/cache/중복, B10 archive, B11 배포/용량 검증.
- 실제 외부 URL pilot, 운영 Firebase/Cloud IAM/스토리지/AI 통합, 부하 시험과 운영 rollout은 미실행이다.
- push·PR·merge·배포는 실행하지 않았다.

## 최종 리뷰

독립 reviewer(`gpt-6-astra`, high)가 `4d31e3c..96786fb` 전체 diff와 Task 9 문서를 검토했다. **Critical/Important/Minor 0, B0 범위에서 병합 가능** 판정이다. XML 결과와 diff check도 독립 확인했다. 다섯 Review Focus(재claim의 옛 쓰기 차단·stale 비용 정산·legacy 복구·잠금 경합·DTO/IO/자원 회귀)를 모두 확인했다.

리뷰가 판단을 보류한 후속 기능/운영 항목 8개는 아래 Ruling으로 각각 처리했다. Deferred minors는 없다.

## 구현 중 판단 기록

계획 실행 중의 판단 29건을 순서대로 보존했다. 테스트 이름·코드 식별자를 제외한 설명을 한국어로 정리했다. 비용은 선택에 따른 부담과 잘못 판단했을 때의 영향을 포함한다.

| 번호 | 결정과 근거 | 비용·후속 부담 |
| --- | --- | --- |
| 1 | 제품 문서가 통합된 최신 origin/main에서 분기했다. 서버 코드는 초기 셋업과 같았다. | 새 main과 기존 계획의 문서 차이를 대조해야 한다. |
| 2 | 최초 실행은 사용자가 승인한 Task 1로 제한했다. 이후 Task는 각 후속 요청으로 진행했다. | 나머지 Task는 최초 승인만으로 완료됐다고 간주할 수 없다. |
| 3 | category 없는 READY도 사유가 없으면 정보 보완으로 표시한다. 불완전한 과거 상태를 숨기지 않는다. | 후속 migration은 누락 사유를 명확히 보관해야 한다. |
| 4 | 공통 DB 테스트에 호스트 포트 준비 대기를 추가했다. 기존 연결 실패 31건의 원인을 해소했다. | 테스트 시작 시간이 조금 늘어난다. 운영 코드/schema는 바꾸지 않았다. |
| 5 | 가격을 BigDecimal과 JSON 전용 serializer로 처리한다. Double 변환의 정밀도 손실을 재현했다. | 전용 serializer를 유지해야 한다. |
| 6 | 취소 예외 재전파와 Ktor 테스트 엔진의 500 진단 응답을 구분한다. | 통합 assertion 일부가 엔진 진단 형식에 의존한다. |
| 7 | 쓰기 guard는 명시적 transaction을 요구한다. autoCommit에서는 잠금이 결과 쓰기를 보호하지 못한다. | 호출자가 transaction을 시작하고 종료해야 한다. |
| 8 | Task 5에서 Worker claim 전달과 임시 최종 쓰기 보호를 선행했다. | 임시 최종 SQL을 Task 6에서 교체해야 했으며 교체했다. |
| 9 | category 없는 Complete는 Partial과 AI_INVALID_CANDIDATE로 처리하고 사용자 누락 사유를 보존한다. READY에는 유효 category가 필요하다. | 잘못된 처리 결과가 직접 보완 대상으로 표시된다. |
| 10 | 부분/실패 결과는 기존 metadata를 보존하고 없는 값만 채운다. USER/override로 보호된 null도 유지한다. | metadata 갱신은 성공 분석 또는 수동 편집이 필요하다. |
| 11 | browser 인프라 예외도 현재 실행에만 retry/503을 적용하고 취소는 재전파한다. | 이전 500/복구 의존 동작의 테스트를 즉시 retry에 맞춰 변경했다. |
| 12 | token과 claimed version이 없는 legacy RUNNING은 만료되거나 null인 lease로 복구한다. | 비정상 과거 행도 중단된 실행으로 취급하지만 유효 claim 없이 쓰지는 못한다. |
| 13 | 잠금 없는 발견 후 후보별 transaction에서 item→job SKIP LOCKED 순서로 다시 검증한다. | 사용 중인 후보는 다음 검사로 넘긴다. 완료된 전이만 복구 수에 포함한다. |
| 14 | 공통 잠금 함수에 기본 false인 skipLocked 옵션을 추가했다. | 내부 함수에 복구용 모드가 하나 늘었다. 기존 claim/guard의 대기 동작은 유지한다. |
| 15 | browser lease 복구 검증은 공통 reconciler 테스트에 둔다. | browser 복구 검증의 위치가 Worker 테스트와 분리된다. |
| 16 | route가 동기 service 함수를 받을 수 있게 overload를 추가했다. | route 조립 API가 하나 늘었다. production과 테스트가 같은 경계를 사용한다. |
| 17 | 종료 후 등록은 새 자원을 닫고 거부하며 같은 객체는 한 번만 닫는다. | 호출자가 등록 실패를 처리해야 한다. 여러 종료 실패는 suppressed로 보존한다. |
| 18 | 직접 초기화한 Firebase app만 종료하고 기존 default app은 재사용한다. | 기존 app의 project와 배포 설정이 일치해야 한다. |
| 19 | 생성 connection을 반환한 뒤 동기 발행한다. 제한된 pool에서 connection 고갈을 막는다. | 생성 응답은 외부 발행 전 transaction의 snapshot이다. commit 후 취소에도 replay는 가능하다. |
| 20 | 최초 구현은 발행과 종료를 같은 잠금으로 직렬화했다. 종료 경계의 원자성을 우선했다. | 종료 대기뿐 아니라 동시 생성 발행의 처리량도 제한했다. 후속 리뷰에서 확인해 작업 수 집계와 잠금 밖 발행으로 교체했다. |
| 21 | 최종 코드·Task 9 문서를 한 번의 독립 리뷰로 검토했다. | 리뷰 시 미커밋인 문서를 최종 커밋과 대조해야 한다. |
| 22 | 전체 생성/replay 응답·상세 GET·clientCreatedAt는 B1이다. | 현재 응답의 상수/null 필드가 제품 계약을 완성하지 못한다. |
| 23 | 일반 추출/retry의 null failureCode는 B1 공개 mapper/B5 실패 분류에서 보완한다. | 현재 일부 실패에는 구체적인 공개 사유가 없다. |
| 24 | category/purpose ownership·삭제/version·FK는 실자원 구현 이후에 연결한다. | 현재 snapshot은 실제 자원 삭제/수정의 최종 유효성을 보장하지 않는다. |
| 25 | 현재 후보 공급은 로컬 catalog이며 향후 DB 공급은 connection 공유와 pool 경계를 설계한다. | 별도 connection을 무조건 열면 pool 고갈이나 대기가 생길 수 있다. |
| 26 | browser/maintenance runtime·Scheduler/private 인증·generation 전체 재시도 예산은 B5다. | B0 단독으로 운영 경로와 전체 retry 정책이 완성되지 않는다. |
| 27 | 사용량 미상 또는 만료 예약의 보수적 최대 비용 정산을 유지한다. | 실제 사용량이 없으면 최대 비용이 계상될 수 있다. |
| 28 | 사용자 변경·media·cache/list/archive는 후속 묶음이다. | 아직 해당 제품 기능을 공개 API로 이용할 수 없다. |
| 29 | 운영 IAM·외부 AI/browser·용량·rollout은 별도 검증이다. | 로컬 테스트 통과가 운영 준비 완료를 보장하지 않는다. |

## 후속 리뷰 반영

초기 완료/리뷰는 `70ced87` 시점의 기록이다. 이후 사용자 외부 리뷰에서 전체 실행 시간과 task 마감의 불일치, 발행 직렬화, 오류 관측 누락을 확인했다. [보완 기록](b0-review-hardening-2026-10-05.md)에 수정·검증과 B5 인계를 기록한다. 초기 리뷰의 지적 없음 판정은 이후 발견된 결함이 없다는 의미가 아니다.
