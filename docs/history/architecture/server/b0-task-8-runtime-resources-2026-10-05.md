# B0 Task 8 — 서버 IO와 연결 자원 수명 관리

> 날짜: 2026-10-05 · 브랜치: `server/b0-foundation` · 기준: `ed6edcc`

## 구현

DatabaseFactory를 Main에서 추출하고 HikariCP 7.0.2를 명시적으로 추가했다. 기존 nonpool datasource와 migrate는 유지하며 runtime은 역할별 pool을 하나씩 만든다. 기본 maximum은 API 5·일반 Worker 2, minimumIdle 0·획득 timeout 5000ms다. DB_POOL_MAX_SIZE의 양의 정수 override와 config 자체의 timeout 최소 250ms를 검사한다. LOCAL_HEALTH는 credential과 외부 초기화 없이 동작한다.

공개/일반/browser route의 동기 service 호출을 IO로 옮겼다. 기존 concrete service overload는 동기 함수 overload로 위임한다. 테스트는 단일 route executor와 실제 blocking latch로 호출이 다른 스레드에서 실행되는지 검증한다. Firebase 검증과 dispatcher의 CancellationException도 요청 pipeline으로 전파한다.

RuntimeResources는 identity 중복 등록을 막고 역순으로 한 번씩 닫으며, 하나가 실패해도 나머지를 닫는다. 종료 뒤 새 등록은 닫고 거부한다. stopping과 동기 dispatch 시작은 같은 gate로 직렬화하고, 먼저 허용한 dispatch를 마친 뒤 새 작업을 거부한다. 종료는 진행 중인 Cloud Tasks 호출을 기다릴 수 있다.

Main은 역할 pool·HTTP transport·Cloud Tasks client 및 직접 초기화한 Firebase app을 소유하고 ApplicationStopped에서 닫는다. 외부에서 만든 Firebase app은 삭제하지 않는다. 종료 실패 로그는 고정 문장만 남긴다. Cloud Tasks client는 runtime에서 한 번 만든다. JDK 17 HttpClient gateway는 기존 재사용을 유지하고 존재하지 않는 close API는 호출하지 않는다.

SafeHttpTransport는 OkHttp dispatcher/pool을 공유하되 요청별 DNS identity와 pinned 주소 복사본을 사용한다. 서로 다른 pins의 기존 connection을 재사용하지 않는다. close는 신규 fetch를 거부하고 dispatcher 진입 전 call까지 취소한 뒤 executor/pool을 정리한다. 기존 timeout·redirect/proxy/retry 금지·512KiB HTML 제한을 유지한다.

## pool 도입에 따른 생성 경계 보완

기존 생성은 connection을 잡은 채 dispatch callback을 호출했고 dispatcher는 두 번째 connection을 요구했다. bounded pool이 가득 차면 발행이 timeout되는 실제 위험이어서 생성 service를 함께 보완했다. transaction 안에서 응답 snapshot을 읽고 commit·connection 반환을 마친 뒤 동기 발행한다. max=1 pool에서 실제 outbox claim·gateway 호출·완료 기록까지 확인했다.

발행 fault는 durable outbox의 후속 재처리에 맡기고 cancellation은 전파한다. 취소 뒤 이미 commit한 상품은 replay할 수 있다. 실제 dispatcher가 gateway 취소를 삼키는 기존 경로도 lease 해제 뒤 rethrow하도록 보완했다. lease 정리 오류가 있어도 원래 취소를 보존한다.

## 검증과 검토

pool/resources/config/module 및 동기 함수 route 기반이 없는 상태의 컴파일 실패를 확인했다. 기반을 구현한 뒤 14개 중 blocking route 스레드와 Firebase 취소 전파 3개 assertion 실패를 확인했다. HTTP lifecycle 부재도 close/use 컴파일 실패로 확인했다. 첫 관련 25개에서 pin 변경 fixture만 실패했고 Mac에 127.0.0.2가 없음을 socket bind probe로 확인했다. 시스템 설정을 바꾸지 않고 IPv4·IPv6 loopback으로 동일 host/port의 두 서버를 구성해 모두 통과했다.

max=1 실제 발행과 commit 후 취소 2개 테스트는 기존 생성 service에서 실패한 뒤 보완했다. 관련 32개가 통과했다. 실제 Worker module의 pool 획득·반환·application stop 후 PostgreSQL 연결 종료와 진행 중 HTTP close 취소도 확인했다.

읽기 전용 코드 검토에서 stopping check와 dispatch 시작 사이 race, 실제 OutboxDispatcher의 gateway 취소 누락 두 건을 확인했다. admission gate 부재의 컴파일 실패와 실제 gateway 취소의 assertion 실패를 각각 확인한 뒤 수정했다. gate는 이미 허용된 동기 작업을 마치고 이후 발행을 거부하며, dispatcher 취소는 lease를 정리한 뒤 전파한다.

최종 `./gradlew test`는 4분 23초에 성공했다. 151개 중 150개 통과, 실패·오류 0개, 실제 외부 URL pilot 1개 opt-in skip이다. JDK 17·Colima를 테스트 process에 지정했다. 신규 pool/IO/lifecycle·실제 발행·gateway 취소·종료 gate 테스트는 모두 실행됐다. 수정 후 whole suite로 두 검토 항목의 해결과 기존 회귀를 함께 확인했다.

## 후속

다음은 Task 9의 전체 B0 쓰기 경로 감사·legacy rollout·회귀 증거·B1 인계 문서 마무리다. 이번 작업은 Task 8이며 B0 전체 완료가 아니다. PR·push·production 작업은 진행하지 않았다.
