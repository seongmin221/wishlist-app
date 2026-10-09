# 서버 로컬 테스트 환경

로컬 검증은 [ADR-014](../../history/architecture/server/ADR-014-local-integration-test-dependencies.md)의 실제 PostgreSQL·인증 emulator·queue/AI fake 경계를 따른다. 실행 결과는 구현 이력에, 명령과 환경 확인 근거는 이 문서에 기록한다.

## 전체 테스트 실행

server/에서 JDK 17과 사용 가능한 Docker API socket을 지정한다. 아래는 macOS Homebrew JDK 17과 `/var/run/docker.sock`을 쓰는 Podman 환경의 예시다. 다른 기기에서는 JDK 경로·socket·호스트 주소를 환경에 맞게 바꾼다.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
DOCKER_HOST=unix:///var/run/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 \
RUN_REAL_URL_PILOT=0 ./gradlew test --rerun-tasks
```

`RUN_REAL_URL_PILOT=0`은 외부 URL pilot을 실행하지 않는 설정이다. 실제 결과는 통과·실패·오류·skip으로 구분하여 기록한다. `--rerun-tasks`는 이전 Gradle 실행 결과를 재사용하지 않고 전체 작업을 실행한다.

## Testcontainers socket 선택

- `DOCKER_HOST`는 Docker client가 사용할 endpoint 지정이다. 로컬 Testcontainers 설정에 특정 provider가 지정돼 있으면 실제 선택한 provider와 socket도 함께 확인한다.
- `UnixSocketClientProviderStrategy`는 `/var/run/docker.sock`을 사용한다. 이 경로가 Podman machine socket을 가리키는 환경에서는 Podman으로 연결된다. 이 provider가 선택되면 오래된 Colima DOCKER_HOST 문자열만으로 실제 연결을 판단할 수 없다.
- `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`는 컨테이너에 전달할 socket 경로를 지정한다. Docker client의 provider를 Podman으로 전환하는 설정은 아니다.
- `TESTCONTAINERS_HOST_OVERRIDE`는 테스트에서 container의 공개 port에 접근할 호스트 주소다. VM의 port forwarding과 함께 맞아야 한다.

PostgresTestContainer는 PostgreSQL 준비 로그와 호스트 port 준비를 함께 기다린다. 보완 배경은 [호스트 준비 이력](../../history/architecture/server/db-test-host-readiness-2026-10-05.md)에 있다.

## B4 baseline의 실행 환경 기록

[B4 baseline](../../history/architecture/server/b4-read-api-implementation-2026-10-07.md#수신과-baseline)(2026-10-07)은 다음 환경과 명령으로 실행했다. 표준 출력의 임시 파일 redirect는 생략했다. 임시 로그 경로는 검증 근거로 보존하지 않는다.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home DOCKER_HOST=unix:///Users/user/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 RUN_REAL_URL_PILOT=0 ./gradlew test --rerun-tasks
```

당시 Colima socket은 없었다. `~/.testcontainers.properties`의 `docker.client.strategy`는 `org.testcontainers.dockerclient.UnixSocketClientProviderStrategy`였고, `ls -l /var/run/docker.sock`의 대상은 `/Users/user/.local/share/containers/podman/machine/podman.sock`이었다. provider 설정과 socket 대상, 성공한 DB 테스트를 근거로 Podman 사용 환경을 확인했다.

위 명령은 당시 실행 기록이며 일반 재현용으로 복사하지 않는다. 다음 실행에서는 이 문서 첫 절의 유효한 Podman DOCKER_HOST 예시를 사용한다. 환경 예시로 과거 실행 기록을 소급해서 바꾸거나 시스템 socket을 변경하지 않는다.


## 제한 환경의 B4 전체 회귀 시도

2026-10-09에는 공용 Gradle 캐시 대신 임시 캐시 복사본과 이미 설치된 Gradle9.7.1을 사용해 전체 회귀를 시도했다. 표준 출력 redirect는 생략한 실제 명령이다.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/tmp/wishlist-b4-review-gradle DOCKER_HOST=unix:///var/run/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 RUN_REAL_URL_PILOT=0 /Users/user/.gradle/wrapper/dists/gradle-9.7.1-bin/1w1c7tv4s851m17nbqdsro2tv/gradle-9.7.1/bin/gradle test --rerun-tasks --offline --no-daemon
```

Gradle의 local socket 생성이 sandbox에서 거절돼 build 시작 전 exit1이었다. DB 테스트·processResources·migration job이 실행된 결과로 기록하지 않는다. 임시 캐시 경로와 설치 경로는 재현용 공통 설정이 아니므로, 제약이 없는 환경에서는 첫 절의 wrapper 명령을 사용한다.


권한 전환 후에는 첫 절의 표준 Podman wrapper 명령으로 전체 회귀를 완료했다. 결과는 [B4 전체 검증 이력](../../history/architecture/server/b4-read-api-implementation-2026-10-07.md#권한-전환-후-전체-회귀와-배포-job-검증)에 구분했다. 앞선 제한 환경의 명령을 일반 실행 절차로 대체하지 않는다.
