# Runtime IO와 자원 수명

> 구현 범위: B0 Task 8 · B5 browser/maintenance 역할 · 운영 instance별 총 DB 연결 검증은 B11

## 요청 실행 경계

공개 생성과 일반·browser Worker route는 동기 service 호출만 `withContext(Dispatchers.IO)`에서 실행한다. body 파싱·응답과 service의 동기 JDBC 인터페이스는 유지한다. service overload는 같은 route의 동기 함수 overload로 위임한다. 테스트는 route 실행을 단일 스레드에 고정하고 blocking 콜백을 latch로 멈춰 IO 실행 스레드가 분리됨을 확인한다.

Firebase token 검증도 기존 IO 경계를 유지하며 CancellationException을 UNAUTHORIZED로 바꾸지 않고 다시 던진다. route/Worker/service/실제 outbox gateway의 취소는 요청 pipeline으로 전파한다. IO 전환 자체가 동기 JDBC나 외부 SDK를 즉시 중단시키지는 않는다. 취소 이후의 상품 쓰기는 기존 claim guard와 lease 복구로 보호하고, 이미 commit된 생성은 idempotency replay와 durable outbox로 복구한다. 내부 fire-and-forget 발행은 만들지 않는다.

## 역할별 DB pool

DatabaseFactory를 Main에서 별도 파일로 추출했다. 기존 PGSimpleDataSource 및 Flyway migrate 함수는 테스트·단발 도구에 유지한다. runtime은 HikariCP 7.0.2를 명시적으로 사용하고 역할마다 pool 하나를 RuntimeResources에 등록한다.

| 설정 | API | 일반 Worker |
| --- | --- | --- |
| maximumPoolSize 기본값 | 5 | 2 |
| minimumIdle | 0 | 0 |
| connectionTimeout | 5000ms | 5000ms |
| DB_POOL_MAX_SIZE | 양의 정수 override | 양의 정수 override |

LOCAL_HEALTH는 pool·Firebase·Cloud Tasks 초기화 없이 `/health`를 제공한다. 잘못된 production credential·role·pool override는 기존 정책처럼 시작 때 실패한다. API/Worker instance 수를 곱한 운영 연결 한도는 B11에서 검증하며 이번 작업에서 운영값을 확정하거나 배포하지 않는다.

pool config는 양의 크기와 250ms 이상의 획득 timeout을 요구한다. 라이브러리 제약은 [HikariCP 7.0.2 공식 설정 문서](https://github.com/brettwooldridge/HikariCP/blob/HikariCP-7.0.2/README.md#frequently-used)를 기준으로 확인했다. 실제 PostgreSQL max=1에서 timeout·반환 후 재사용·close 후 획득 거부를 검증한다.

## 생성 후 발행과 connection 반환

생성 transaction에서 응답용 상품 snapshot을 읽고 commit한 뒤 connection을 반환한다. 이후에만 동기 outbox 발행 콜백을 실행한다. 생성 connection을 쥔 채 dispatcher의 두 번째 connection을 기다리면 bounded pool이 찼을 때 발행이 timeout되므로, 이 경계를 pool 도입과 함께 보완했다. max=1 pool에서도 실제 OutboxDispatcher가 claim·발행·완료 기록까지 수행한다.

일반 발행 오류는 생성 성공을 취소하지 않고 scheduler가 durable outbox를 재처리한다. CancellationException은 실제 dispatcher가 outbox lease를 해제한 뒤 다시 던지고 생성 service도 전파한다. 이미 commit한 상품은 replay 가능하며 동일 key로 job/outbox를 추가하지 않는다.

outbox 발견의 lease 만료 비교와 새 120초 lease 저장은 모두 DB clock_timestamp를 사용한다. JVM Instant.now와 DB 시각을 섞지 않는다. 테스트에서는 DB clock을 호스트보다 30초 앞당겨 같은 event의 lease 기간과 중복 발행 차단을 검증한다.

## 자원 등록과 종료

RuntimeResources.own은 동일 object identity를 한 번 등록한다. close는 등록 역순으로 한 번씩 닫는다. 하나가 실패해도 나머지를 닫고 나머지 예외를 suppressed로 보존한다. 중복 close는 안전하며, 종료 후 새 등록은 자원을 닫고 거부한다. 이미 등록된 자원은 다시 닫지 않는다.

ApplicationStopping은 dispatch admission을 닫는다. `runIfOpen`은 짧은 잠금 안에서 진행 중 작업 수만 증가시키고 네트워크 호출은 잠금 밖에서 실행한다. 서로 다른 생성 요청의 발행은 동시에 진행한다. stop은 새 admission을 닫고 진행 중 작업 수가 0이 될 때까지 기다린다. 이미 허용된 동기 발행을 마친 뒤 stopping이 진행하고, 이후 콜백은 새 outbox를 claim하거나 task를 등록하지 않는다. createTask RPC는 숨은 재시도 없이 총 5초로 제한한다. 종료는 이미 허용된 발행의 완료와 후속 DB 정리를 기다린다.

ApplicationStopped에서 자원을 닫는다. 종료 실패 로그는 고정 문장만 기록하고 예외·credential을 출력하지 않는다. API는 pool, 직접 초기화한 Firebase app, Cloud Tasks client를 소유하므로 Tasks→Firebase→pool 순서로 닫는다. 외부에서 이미 만든 default Firebase app은 재사용하지만 삭제하지 않는다. 일반 Worker는 pool·HTTP transport·처리 executor를 소유해 executor→transport→pool 순서로 닫는다.

CloudTasksClient는 요청마다 생성하지 않고 API runtime에서 한 번 생성해 재사용한다. 일반 Worker의 JDK 17 HttpClient는 OpenAiResponsesGateway에서 기존처럼 재사용하며 지원하지 않는 close API를 호출하지 않는다.

## HTTP transport와 DNS 고정

SafeHttpTransport는 OkHttp dispatcher/connectionPool을 공유하되 유휴 연결 수는 0으로 둔다. DNS identity가 달라 연결 재사용이 불가능하므로 오래된 연결을 보관하지 않는다. 각 요청은 별도 DNS identity로 pinned 주소 복사본만 사용하며 proxy·자동 redirect·자동 retry를 끈다. 같은 host의 이전 pinned connection을 재사용해 새 주소 검증을 우회하지 않는다. IPv4와 IPv6 loopback의 서로 다른 서버를 같은 host/port로 호출해 pin 변경을 검증한다.

fetch는 call을 lock 아래 등록한 뒤 외부 IO를 수행한다. close는 신규 call을 거부하고 dispatcher에 들어가기 전 call을 포함해 등록된 call을 취소한 뒤 executor와 pool을 종료한다. 응답 사용과 등록 해제는 finally에서 수행한다. 호출당 최대 15초와 HTML 512KiB 제한을 유지하며 Worker 내에서는 남은 처리 예산으로 timeout을 줄인다.

## 분석 전체 시간 제한

`AnalysisTiming`의 처리 80초 < Worker 응답 90초 < Cloud Tasks dispatch 105초 < DB lease 120초를 테스트로 고정한다. WorkerExecution은 claim·처리·finish를 bounded executor에서 실행하고 queue 대기까지 포함해 90초가 지나면 요청에 RETRY를 반환하고 작업 interrupt를 시도한다. redirect HTTP·token 계산·LLM·browser launch/navigation은 같은 단조 시계의 남은 80초 예산을 사용한다. 처리 예산을 넘긴 결과는 성공으로 반영하지 않고 guarded retry로 전환한다.

executor queue에서 꺼낼 때와 양 lane의 claim 전에 남은 예산을 확인한다. 이미 마감된 전달은 RETRY만 반환하고 DB claim·attempt·outbox를 변경하지 않는다. claim의 pool/owner/item/job 잠금 대기로 마감될 수도 있으므로 owner/item/job 잠금과 DB 시각을 얻은 뒤, 한도 실패 또는 attempt 증가 전에 다시 확인해 transaction을 rollback한다. SDK의 밀리초 timeout으로 변환하기 전 1ms 미만이면 마감 예외를 던져 무제한 timeout 0을 만들지 않는다.

유료 LLM timeout은 예산 markInFlight의 DB 작업이 끝난 뒤 계산한다. 이 지점에서 마감됐으면 미전송임을 명시하고 예약을 해제한다. 실제 client.send에 들어간 이후의 timeout/통신 오류는 사용량 불명확 비용 정산 정책을 따른다.

JVM interrupt가 모든 JDBC/SDK 호출을 즉시 멈추는 것은 아니다. 반환하지 않는 작업은 executor의 제한된 slot을 계속 사용하되 요청 응답은 기다리지 않는다. 늦게 끝난 retry는 PENDING+새 outbox를 원자 저장하고, 끝나지 않은 RUNNING은 120초 lease로 복구한다. 사용량이 도착한 실제 AI 비용 정산은 stale 결과 폐기와 별도로 유지한다. lease heartbeat를 추가하지 않는다.

예상치 못한 API 오류는 requestId·예외 타입·발생 stack frame을 ERROR로 기록한다. 예외 메시지에는 SQL/credential/사용자 입력이 포함될 수 있어 로그와 공개 응답에 넣지 않는다. Ktor 요청 오류(400/404/413/415)는 안전한 4xx envelope로 반환하며 취소는 재전파한다.

## B2 category 후보 공급

Worker의 custom 후보는 잠금 transaction의 동일 connection으로 읽는다. candidate snapshot 저장 후 connection을 반환한 다음 gateway를 호출한다. max pool 1에서도 category 편집과 외부 AI 호출이 서로 DB connection을 기다리지 않는 회귀를 유지한다. 입력 토큰 preflight 단계(B3부터 목적 포함 최대 여덟 단계)는 기존 Worker 처리 시간과 2,500/80 비용 상한을 공유한다. custom·목적 축약 후에도 초과하면 그 호출만 공용 taxonomy로 분류한다. B5 Scheduler/browser runtime 조립은 추가하지 않았다.

## B5 역할과 자원

| 역할 | route | 소유 자원(닫는 순서는 등록 역순) | pool 기본 |
| --- | --- | --- | ---: |
| general-worker | `/internal/worker/general` | pool·BoundedResolver·WorkerExecution·SafeHttpTransport | 2 |
| browser-worker | `/internal/worker/browser` | pool·BoundedResolver·WorkerExecution (EgressProxy는 render마다 생성·종료) | 2 |
| maintenance | `/internal/maintenance/run` | pool·Cloud Tasks client | 2 |

API 역할은 `apiRoutes`의 공개 route만 등록한다. browser-worker는 general-worker와 같은 OpenAI 설정을 요구한다. maintenance는 DB 설정을 요구하고 production에서는 Tasks 설정도 필수다. local에서 Tasks 설정이 없으면 발행·조회가 항상 실패하는 gateway를 써서 상태를 바꾸지 않는다. maintenance 실행은 `runIfOpen` 안에서만 하며 종료 중에는 503이다.

`BoundedResolver`는 InetAddress 해석을 크기와 queue가 제한된 daemon pool에서 실행하고 남은 처리 시간만 기다린다. `getAllByName`은 interrupt로 멈추지 않으므로 포화되면 기다리지 않고 거부한다. 시간 초과한 조회가 아직 queue에 있으면 queue에서 빼 slot을 비운다. browser-worker는 한 페이지가 subresource host를 한꺼번에 검사하므로 resolver를 8 threads·queue 128로 키운다(general-worker는 4·16). `EgressProxy`는 render마다 새로 만들고 render가 끝나면 닫는다. 공유 proxy의 정리가 timeout 직후 겹친 다른 render의 연결을 끊는 일을 막기 위해서다. 연결마다 slot을 쓰고 30초 socket timeout을 둔다. proxy 연결은 Worker thread 밖이라 처리 deadline을 직접 쓰지 않으며, navigation timeout과 render 종료가 실제 상한이다.

maintenance는 outbox 발행·RUNNING 복구·PENDING 복구·budget을 순서대로 실행한다. 발행은 전체 50초 중 30초만 쓰고, 두 복구 단계는 50초 deadline을 공유해 후보마다 남은 시간을 확인하며 넘으면 `timeout:<step>`으로 보고한다. DB만 쓰는 budget 정리는 queue 장애 중에도 만료 예약이 쌓이지 않도록 마감과 무관하게 항상 실행한다.
