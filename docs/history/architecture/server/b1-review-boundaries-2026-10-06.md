# B1 리뷰의 응답·의존성·입력 경계 보완

> 2026-10-06 · `server/b1-item-detail-create-api` · 최초 B1 구현 후 사용자 리뷰 반영

## 변경과 근거

- **생성 응답:** 생성 transaction에서 읽고 commit한 snapshot을 반환한다. 발행 후 재조회와 callback 내부 UPDATE를 사용하는 기존 테스트를 제거했다. commit 뒤 추가 연결을 거부하는 실제 PostgreSQL fixture에서도 생성 결과가 유지됨을 검증했다. 성공적인 생성·즉시 발행의 pool 대여가 4회에서 3회로 줄며, 이후 최신 분석 값은 GET/replay에서 읽는다.
- **의존성:** `WishlistItemViewMapper`를 `app.http`로 이동했다. `app.wishlist`가 HTTP DTO를 참조하지 않으며 `Main`에서 생성·조회 서비스를 각각 주입한다. 생성 서비스의 내부 repository 노출과 조회 callback의 null 기본값을 제거했다.
- **repository:** 상태와 상품 projection을 겸하던 repository를 `WishlistItemRepository`로 명명했다. 미사용 상태 전용 API를 제거하고 생성 item/job/outbox SQL을 repository로 옮겼다. key 충돌에는 기존 ID 조회 뒤 재조회하는 대신 owner/key 조건의 전체 projection 하나를 사용한다. 생성 transaction·원자성·동시 key 재전송 계약은 유지했다.
- **실패 코드:** 도메인 `AnalysisFailureCode` enum이 공개 여부와 category 누락 사유를 정의한다. HTTP DTO/presenter와 Worker의 새 실패 저장·최종 사유 계산이 같은 enum을 사용한다. 알 수 없는 legacy 진단은 공개 경계에서 안전한 fallback으로 처리하며 저장 진단값과 V8 SQL은 유지했다. 미연결 추출 code는 기존 공개 계약의 예약 범주이며 B5 producer 완료를 의미하지 않는다.
- **입력:** POST의 JSON wire 타입·시각 검증을 순수 `parseCreateRequest`로 분리했다. GET ID와 Idempotency-Key는 같은 helper로 정규 UUID 형식을 확인하고 대소문자는 허용한다. UTC Instant와 offset 현지 시각 양쪽의 연도를 1~9999로 검증했다.
- **JDBC 시각:** 공유 시각은 `OffsetDateTime` 바인딩/조회로 전환했다. 1582년 이전 날짜를 UTC SQL 값으로 검증해 Timestamp의 달력 경로에서 발생하는 날짜 이동을 막았다. microsecond 아래 입력 자릿수를 절삭해 PostgreSQL 반올림으로 마지막 허용 Instant가 연도 10000에 넘어가지 않게 한다. 날짜 범위를 임의로 최근 시각으로 좁히지는 않았다.
- **테스트:** 요청 파서·실패 code presenter의 순수 단위 테스트, ARCHIVED GET과 빈 조치, 대문자 UUID, 시각 범위 밖 입력, 최초 공유 시각이 null인 key 재전송을 추가했다.

구체적인 현재 계약은 [B1 조회 문서](../../../architecture/server/wishlist-item-read-api.md)에 반영했다. Scheduler가 있는 환경의 fire-and-forget 발행과 API/Worker body 상한은 후속 운영 설정 검토다. 이번에는 기존 동기 createTask 5초 상한을 유지한다. key 재전송은 API 결과를 복구하며 미발행 event를 즉시 재발행하지 않는다. queue 전달 복구 runtime은 B5에서 연결한다.

## 검증

기존 B1의 190개 강제 회귀 결과 이후 리뷰 회귀를 추가했다. 커밋 후 연결 장애·비정규 key·UTC 연도 경계·1582년 이전 저장 날짜에 대해 변경 전 실패를 관측했고, 수정 후 영향받는 HTTP/DB/Worker 테스트를 통과했다. 연도 9999의 최종 Instant 정밀도 단위 회귀도 실패 후 절삭 처리로 통과했다.

```bash
# server/에서 실행
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
DOCKER_HOST=unix:///Users/user/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 \
RUN_REAL_URL_PILOT=0 ./gradlew test --rerun-tasks
```

최종 실제 PostgreSQL Testcontainers 강제 전체 실행은 **200개 중 199 통과, 실패/오류 0, opt-in RealUrlPilot 1 skip**이며 `BUILD SUCCESSFUL in 3m 23s`다. 외부 AI·실 URL·운영 배포는 미검증이다. 별도 읽기 전용 리뷰에서 Critical/Important 지적 없음. 현재 architecture 문서에 남은 옛 repository 이름과 재전송 복구 표현을 보완했다. whitespace·문서 상대 링크 검사도 통과했다.

## 커밋과 범위

기존 B1 세 커밋을 보존하고 후속 로컬 커밋으로 분리했다.

1. `4353ce1` — 생성 snapshot·JDBC/UTC/UUID 경계·HTTP presenter·서비스 주입·repository·회귀. 다음 Worker 통합에 사용할 실패 enum의 기반을 포함한다.
2. `edcac6d` — Worker 실패 저장·최종 category 사유를 같은 도메인 enum에 연결.
3. 현재 계약·INDEX·본 리뷰 구현 기록 갱신.

작성자/커미터 이메일은 `seongmin221@naver.com`이며 한글 커밋 규칙을 지켰다. push·PR 생성·병합·배포는 수행하지 않았다. 원본 인계 문서와 수신 기록은 보존하고 커밋에 포함하지 않았다.
