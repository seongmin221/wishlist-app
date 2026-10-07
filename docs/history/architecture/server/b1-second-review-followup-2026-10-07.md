# B1 2차 코드 리뷰 후속 보완

> 2026-10-07 · `server/b1-item-detail-create-api` · PR #8 리뷰 지적 반영

## 변경과 근거

- **즉시 발행 실패 로그:** 생성 직후 지정 발행이 실패하면 dispatcher가 `false`를 반환하거나 생성 서비스가 예외를 삼켜 흔적이 없었다. `dispatchPending`을 호출하는 Scheduler가 아직 없으므로 실패한 상품은 기록 없이 PROCESSING에 남는다. gateway 실패는 `OutboxDispatcher`, 그 밖에 생성 서비스까지 전파된 예외는 생성 서비스가 event ID와 예외 타입만 warn으로 남긴다. 예외 메시지는 요청 데이터를 담을 수 있어 기존 오류 로그 정책처럼 제외했다.
- **생성 시점 loopback 판정:** `URI.host`는 대소문자를 유지하고 IPv6 literal을 `[::1]`처럼 괄호째 반환해 `::1`, `LOCALHOST` 비교가 동작하지 않았다. host를 정규화하고 IPv4 `127.0.0.0/8`·`0.0.0.0`, IPv6 loopback·unspecified literal, `*.localhost`를 거절한다. IPv4는 직접 파싱하고 IPv6는 `:` 포함 literal만 `InetAddress`로 해석해 요청 경로에서 DNS를 조회하지 않는다. 네트워크 경계는 여전히 extraction의 `UrlSafetyPolicy`다.
- **`AnalysisFailureCode.isPublic` 제거:** 모든 값이 `true`라 필터 역할을 하지 않았다. enum 전체를 공개 어휘로 정의하고 mapper는 알려진 code를 그대로 반환한다.
- import 순서와 `Main`의 전체 경로 표기를 정리했다.

## 남긴 항목

생성 서비스의 repository 주입은 transaction 조정에 DataSource가 함께 필요해 생성자 형태만 바뀌고 이득이 작아 1차 후속 기록대로 별도 작업으로 남긴다.

## 검증

loopback 변형 9개 거절과 유사하지만 공개인 host 3개 허용을 생성 서비스 테스트에 추가했다. 실제 PostgreSQL Testcontainers 전체 실행(`RUN_REAL_URL_PILOT=0 ./gradlew test`) 결과 **203개 중 실패/오류 0, opt-in RealUrlPilot 1 skip**이다.
