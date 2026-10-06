# B1 코드 리뷰 후속 보완

> 2026-10-06 · `server/b1-item-detail-create-api` · 브랜치 전체 코드 리뷰의 우선 수정 4건 반영

## 변경과 근거

- **URL 문자 인코딩:** `java.net.URI`는 짝 없는 surrogate를 허용하지만 JDBC의 UTF-8 인코딩은 이를 `?`로 바꾼다. 저장 URL이 입력과 달라져 같은 key 재전송이 `409 IDEMPOTENCY_KEY_REUSED`가 되고 Worker도 다른 URL을 가져가므로, 생성 서비스가 `422 INVALID_URL`로 거절한다.
- **URL 길이:** `sourceUrl`을 2048자로 제한했다. 상한이 없으면 큰 URL이 저장되고 GET·재전송 응답마다 반복된다. 2048은 일반적인 URL 호환 한도이며 공유 URL의 실제 길이를 관측해 조정할 수 있다. 오류 code는 기존 `INVALID_URL`을 유지한다. body 전체 상한은 계속 후속 운영 설정 범위다.
- **생성 결과 타입:** `CreateResult.itemId`가 `InvalidUrl`에서 `requireNotNull`로 실패할 수 있던 구조를 `CreateResult.Stored` 하위 타입으로 제한했다. item이 없는 결과에서는 컴파일 단계에서 item에 접근할 수 없다. 테스트 fixture는 `createdItemId`로 신규 생성을 단언한다.
- **발행 실패 원인 보존:** outbox lease 해제가 실패하면 cleanup 예외에 원래 gateway 실패를 suppressed로 붙인다. B5 Scheduler의 batch 발행에서 장애 원인을 잃지 않게 한다.

## 후속으로 남긴 리뷰 항목

공용 transaction helper, 생성 서비스의 repository 주입, 조회 모델 평탄화, `AnalysisFailureCode.isPublic` 정리, 문자열 상태값의 enum 통일, 생성 직후 select와 claim SQL 왕복 축소, 생성 응답의 동기 Cloud Tasks 대기는 범위가 커서 별도 작업으로 남겼다.

## 검증

수정 전 URL 거절과 suppressed 보존 회귀 2개가 실패함을 확인했다. 2048자 경계와 surrogate 쌍 재전송 회귀는 과잉 거절을 막는 보호 테스트다. 수정 후 실제 PostgreSQL Testcontainers 강제 전체 실행 결과는 아래와 같다.

```bash
# server/에서 실행
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
DOCKER_HOST=unix:///Users/user/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 \
RUN_REAL_URL_PILOT=0 ./gradlew test --rerun-tasks
```

**203개 중 202 통과, 실패/오류 0, opt-in RealUrlPilot 1 skip**, `BUILD SUCCESSFUL in 3m 23s`. 외부 AI·실 URL·운영 배포는 미검증이다.
