# DB 테스트 호스트 포트 준비 대기

> 날짜: 2026-10-05 · B0 Task 2의 선행 테스트 기반 보완

## 재현과 변경

기존 WishlistRoutesTest를 Colima Docker 및 IPv4 host 설정으로 실행했을 때 Flyway가 Connection refused로 실패했다. PostgreSQL 준비 로그만 기다리는 fixture는 VM의 호스트 port forwarding 준비를 보장하지 못했다.

테스트 전용 PostgresTestContainer를 만들고 기존 PostgreSQL 준비 로그 2회와 Wait.forListeningPort()를 함께 기다리게 했다. 기존 테스트 21개 생성 위치에서 같은 fixture를 재사용한다. production 코드·schema·일반 테스트 assertion을 변경하지 않고, 고정 sleep이나 DB 연결 예외 skip도 추가하지 않았다.

## 검증

같은 WishlistRoutesTest가 변경 후 통과했다. 이후 전체 기존 suite를 실행해 81개 중 80개 통과, 실패 0개, opt-in RealUrlPilotTest 1개 skip을 확인했다. 이전 Task 1의 DB 회귀 31건 연결 실패는 이 선행 보완 뒤 해소됐다.

실행은 server/에서 JDK 17과 아래 환경을 사용했다.

```sh
DOCKER_HOST=unix:///Users/user/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 \
RUN_REAL_URL_PILOT=0 ./gradlew test
```

이 주소는 당시 검증에 사용한 기기의 Colima 경로이며 다른 기기는 해당 Docker socket을 지정한다. 당시에는 시스템 Podman socket이나 Docker 전역 설정을 바꾸지 않았다.
