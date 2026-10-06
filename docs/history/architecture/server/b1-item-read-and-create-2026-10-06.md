# B1 상품 상세·생성 응답·지정 발행 구현

> 2026-10-06 · develop@59c11cc 기반 · Orca `server-b1-handoff`

## 범위와 결정

사용자가 B1 설계와 선택 `clientCreatedAt` 정책을 승인한 뒤 구현했다. 원본 B0 인계 문서와 수신 기록은 보존했다. 기존 임시 worktree에서 SIGTERM으로 중단한 테스트는 통과/실패 판단에 사용하지 않았다.

- owner-scoped 상세 GET을 추가하고 생성·재전송·상세를 `WishlistItemViewMapper`로 통일했다. 실제 이름·이미지·현재 상태·값 출처·정책 조치를 반환한다.
- 다른 owner·없는 항목·DELETED는 같은 404, 잘못된 UUID는 400, 미인증은 401이다. 삭제된 항목의 생성 key 재전송에는 같은 ID의 tombstone을 유지한다.
- 선택 공유 시각은 V10의 nullable `client_created_at`에 별도 보관한다. 시간대가 있는 ISO 8601 문자열만 허용하며 입력 오류는 422다. 생략/null은 null, 유효 재전송은 최초 시각 유지, 서버 저장순은 기존 `created_at` 기준이다.
- 생성의 commit 후 callback에 새 outbox event ID를 전달한다. `dispatchEvent(id)`와 batch 발행은 동일 claim/DB lease를 사용하고, 오래된 backlog가 생성 직후 발행을 대신 소비하지 않게 했다.
- 생성 연결을 반환한 뒤 발행하고 현재 item을 다시 읽는다. 일반 발행 실패에서는 저장된 item/outbox를 보존하며 취소는 전파한다. max=1 pool 회귀도 통과했다.
- 허용한 failureCode만 공개한다. 내부 문자열/누락 code는 상태별 안전한 fallback으로 표현하며 DB 진단값은 유지한다.

가격·통화·브랜드·판매처·metadata 확인 시각은 아직 저장/추출 경로가 없으므로 nullable 유지다. 목적 삭제 영향은 B3/B8, Scheduler·maintenance·장기 PENDING과 전체 retry 예산은 B5, 재분석 검토 상태 계약은 B7 범위다. 기존 production createTask 5초 제한을 유지한다.

계약은 [B1 조회 설계](../../../architecture/server/wishlist-item-read-api.md), 상태는 [API 목록](../../../architecture/server/mvp-api-inventory.md), 후속 순서는 [구현 순서](../../../architecture/server/mvp-api-implementation-order.md)에 반영했다. 제품 API 37개 중 생성·상세 2개 route가 연결됐고 나머지 35개는 후속 작업이다.

## 검증

JDK 17 + Colima + 실제 PostgreSQL Testcontainers에서 실행했다. 외부 OpenAI·실 URL·운영 배포는 이번 검증 범위에 포함하지 않았다.

```bash
# server/에서 실행
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
DOCKER_HOST=unix:///Users/user/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 \
RUN_REAL_URL_PILOT=0 ./gradlew test --rerun-tasks
```

- 변경 전 강제 전체 실행: 177개 중 176 통과, 실패/오류 0, RealUrlPilot 1 skip.
- 구현 전 B1 회귀 7개가 기대한 assertion으로 실패함을 확인했다. 상세 GET·현재 mapper·시각 검증/반환·V10·backlog 지정 발행이 미구현이었다.
- 구현 후 생성·route·상태 repository·outbox·migration·pool 영향 테스트를 통과했다.
- 최종 강제 전체 실행: **190개 중 189 통과, 실패/오류 0, RealUrlPilot 1 skip**, `BUILD SUCCESSFUL in 3m 6s`.
- 새/확장 회귀는 owner/삭제 격리, 실제 metadata·출처·READY/PARTIAL/실패/수동 완료의 공개 조치, 안전한 실패 code, 공유 시각 validation·원본 유지, commit 이후 최신 조회, 지정 event의 missing/lease/published 조건과 batch 동시 발행, V7 및 V9 upgrade 보존을 검증했다.
- 별도 읽기 전용 리뷰에서 Critical/Important 지적 없음. 추적 diff whitespace와 문서 상대 링크 검사 통과.

## 로컬 커밋 분리

구현 검증 후 사용자 지시에 따라 `server/b1-item-detail-create-api`에 다음 순서로 로컬 커밋을 남겼다.

1. `c486207` — 상품 상세·생성/replay mapper·공유 시각/V10·관련 회귀. 다음 지정 발행에서 사용하는 생성 결과의 event ID 전달 기반을 포함한다.
2. `7c8ccab` — 신규 outbox event 지정 발행과 runtime 연결·backlog/lease/복구 회귀.
3. 계약·API 구현 상태·INDEX·본 구현 기록을 갱신하는 문서 커밋.

작성자·커미터는 `seongmin221 <seongmin221@naver.com>`이다. 커밋 전 `./gradlew test`는 UP-TO-DATE로 성공했으며 위 강제 전체 실행 결과와 구분한다. 원본 `HANDOFF.md`와 `HANDOFF-RECEIPT.md`는 커밋에 포함하지 않았다.

push·PR 생성·병합·배포는 수행하지 않았다. B2 등 다음 묶음은 사용자 지시 후 시작한다.
