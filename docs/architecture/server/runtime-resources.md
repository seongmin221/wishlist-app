# Runtime IO와 자원 수명

> 구현 범위: B0 Task 8 · 운영 instance별 총 DB 연결 검증은 B11

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

## 자원 등록과 종료

RuntimeResources.own은 동일 object identity를 한 번 등록한다. close는 등록 역순으로 한 번씩 닫는다. 하나가 실패해도 나머지를 닫고 나머지 예외를 suppressed로 보존한다. 중복 close는 안전하며, 종료 후 새 등록은 자원을 닫고 거부한다. 이미 등록된 자원은 다시 닫지 않는다.

ApplicationStopping은 dispatch admission을 닫는다. `runIfOpen`은 발행 시작과 stop을 같은 gate로 직렬화한다. 이미 허용된 동기 발행을 마친 뒤 stopping이 진행하고, 이후 콜백은 새 outbox를 claim하거나 task를 등록하지 않는다. 따라서 종료는 진행 중인 Cloud Tasks 호출의 완료를 기다릴 수 있다.

ApplicationStopped에서 자원을 닫는다. 종료 실패 로그는 고정 문장만 기록하고 예외·credential을 출력하지 않는다. API는 pool, 직접 초기화한 Firebase app, Cloud Tasks client를 소유하므로 Tasks→Firebase→pool 순서로 닫는다. 외부에서 이미 만든 default Firebase app은 재사용하지만 삭제하지 않는다. 일반 Worker는 pool과 HTTP transport를 소유해 transport→pool 순서로 닫는다.

CloudTasksClient는 요청마다 생성하지 않고 API runtime에서 한 번 생성해 재사용한다. 일반 Worker의 JDK 17 HttpClient는 OpenAiResponsesGateway에서 기존처럼 재사용하며 지원하지 않는 close API를 호출하지 않는다.

## HTTP transport와 DNS 고정

SafeHttpTransport는 OkHttp dispatcher/connectionPool을 공유한다. 각 요청은 별도 DNS identity로 pinned 주소 복사본만 사용하며 proxy·자동 redirect·자동 retry를 끈다. 같은 host의 이전 pinned connection을 재사용해 새 주소 검증을 우회하지 않는다. IPv4와 IPv6 loopback의 서로 다른 서버를 같은 host/port로 호출해 pin 변경을 검증한다.

fetch는 call을 lock 아래 등록한 뒤 외부 IO를 수행한다. close는 신규 call을 거부하고 dispatcher에 들어가기 전 call을 포함해 등록된 call을 취소한 뒤 executor와 pool을 종료한다. 응답 사용과 등록 해제는 finally에서 수행한다. 기존 timeout과 HTML 512KiB 제한을 유지한다.
