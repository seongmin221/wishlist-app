# B2 카테고리 구현과 리뷰 보완

> 2026-10-07 · 구현·독립 리뷰·전체 검증 완료

## 구현 범위와 제품 확인

CAT-01 SELECT/BROWSE와 활성 count·빈 custom·parent filter, CAT-03 생성·owner당20·성공 receipt 기반 rate limit·replay, CAT-02 owner 상세, CAT-04 optimistic version·optional null·parent 고정을 구현했다. 사용자 답변은 [제품 결정 기록](../../product-planning/mvp/decisions/b2-category-api-policy-2026-10-07.md)과 [확정 계약](../../../architecture/server/category-management-api.md)에 보존했다.

V11만 추가했다. 공용 11 group/87 leaf seed, lazy app_users 잠금 기준, 미삭제 normalized UNIQUE, custom/receipt owner FK, item custom 참조 복합 FK와 공용/custom 배타 CHECK를 둔다. owner→category→item→job 순서를 사용하고 Worker 경로에서는 owner 잠금이 category 변경을 막으므로 item/job 이후 category row 잠금을 추가하지 않는다. B1 생성 응답 snapshot·owner GET/replay 공통 mapper·event 지정 즉시 발행과 기존 실행 fence를 유지했다.

owner별 AI 후보를 같은 connection으로 공급하고 snapshot v2에 owner·custom version·구조화된 입력을 저장한다. legacy snapshot은 공용 후보만 신뢰한다. snapshot 재사용과 최종 적용 모두 stale를 검사하며 최신 generation/job/outbox 예약은 기존 시도·최초 시각을 승계한다. 최대 3회 일반 실행·30분 예산을 소진하면 FAILED_RETRYABLE다. B5 Scheduler/browser runtime, B7 재분석 API, B8 category 삭제는 추가하지 않았다.

## 추가 피드백 반영

- 2,000/80 가격표를 유지했다. 전체→설명/예시 제외→최소 이름/상품→공용 taxonomy fallback을 preflight하며 실제 전송 후보에 포함된 ID만 받는다. 저장 원문·custom·수동 지정·AI 적합성은 유지한다.
- 고정 developer 지시문과 JSON user 데이터를 분리했다. custom 구두점은 JSON encoder가 escape하며 기존 category_labels object와 별도 v2 custom_categories를 저장한다.
- 설명 LF/CRLF와 정상 Unicode 결합문자를 허용했다. 길이는 code point, 비교 key는 NFC·공백 정리·Locale.ROOT lowercase다. 대소문자는 이번 사용자 원지시를 근거로 하고 client counter 단위 일치는 후속 client 작업으로 명시했다.
- 동일 내용 PATCH는 version을 유지한다. 빈/공백 예시는 사용자 답변대로 422이며 []는 허용한다.
- 신규 owner는 INSERT ON CONFLICT 뒤 FOR UPDATE한다. ITEM-01과 Worker에도 공통 helper를 적용했다.
- HTTP 오류 code 표, 없는 parent의 400/422, 실패 key 재시도, 삭제 후 replay 409, 성공 receipt 기준 rate count와 owner 잠금 뒤 단일 clock_timestamp·Retry-After 계산을 고정했다.
- custom displayOrder는 생성 시 저장하고 삭제·필터·DB 시각 변화로 다시 매기지 않는다. 생성/편집 후 목록 캐시는 CAT-01을 다시 읽는다.
- inventory/order의 답변 대기를 확정 상태로 갱신하고, 초안 취소의 수명 확인은 별도 미확정 제품 항목으로 분리했다. plan 서비스 create signature에도 parentId를 반영했다.

## 독립 리뷰와 회귀

기초 입력 리뷰의 strict expectedVersion·Unicode FORMAT 보완은 [선행 기록](b2-category-foundation-2026-10-07.md)에 있다. 설계 리뷰의 stale 무한 예산 재설정·토큰 상한·legacy 호환·custom assignment CHECK 문제를 계약과 구현에 반영했다.

구현 읽기 리뷰에서 Important 두 건을 받았다. 유효 snapshot 결과가 CONFIRMED/DEFERRED 기존 AI category·purpose를 덮는 문제와 malformed snapshot 키 누락의 NoSuchElementException을 테스트 실패로 재현했다. 연결 보호 조건과 JSON 구조 검증을 보완했고 재리뷰에서 추가 Critical/Important 지적은 없었다. DB 시각 변경 후 displayOrder 배열 순서도 실제 실패를 확인하고 저장 순서 정렬로 보완했다.

새 회귀는 19개 동시 생성, normalized 동시 중복, 성공4건 상태의 동시2건 rate limit, 잠금 대기 뒤 시간 기준, owner/operation key namespace, no-op PATCH, 실패 key 재시도·삭제 receipt, version 경합·owner 복합 FK, V10 upgrade·11/87 seed, 양 lane stale·3회 소진, owner/삭제/적합성/legacy/malformed 후보, max pool1 외부 AI 중 편집, 최대 custom 입력 fallback·전송 안 한 ID 거절을 포함한다.

첫 B2 전체 실행은 tests=245, failures=4, errors=0, skipped=1, exit 1(4분9초)이었다. 기존 목적 신규 연결 회귀와 browser/general metadata fixture의 비공용 ID, 잠금 대기 통계 조회 테스트가 실패했다. CONFIRMED/DEFERRED의 기존 목적 연결은 보호하되 기존 B0의 null·UNASSIGNED 목적 슬롯에는 신규 AI 연결을 허용하고 review 값을 보존했다. browser/general metadata 회귀 fixture의 CAT_TEST는 실제 공용 C026으로 바꿨다. 후보 검증을 약화하지 않았다. 잠금 대기 테스트의 pg_stat_activity 조회는 같은 transaction의 통계 snapshot을 매회 pg_stat_clear_snapshot으로 갱신하도록 수정했다. 이 실패 실행은 통과 근거가 아니다. 수정 후 네 실패 회귀와 기존 확정 AI 연결 보호를 포함한 targeted 5개는 실패/오류/skip 0으로 통과했다.

## 전체 검증

선행 baseline은 203개 중202 통과·1 skip, 입력 기반 이후 단독 전체는214개 중213 통과·1 skip이다. 최종 B2 단독 전체 실행은 BUILD SUCCESSFUL, exit 0, 4분5초다. XML 집계는 tests=245, failures=0, errors=0, skipped=1로 **244 통과·RealUrlPilot1 skip**이다. B1/Worker 보호 회귀도 포함했다. git diff --check와 기존 V1~V10 무변경·docs 링크를 확인했다.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home DOCKER_HOST=unix:///Users/user/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 RUN_REAL_URL_PILOT=0 ./gradlew test --rerun-tasks
```

push·PR·병합 없이 의미별 로컬 커밋으로 남긴다. 실제 OpenAI/production 배포 검증은 실행하지 않았고 RealUrlPilot은 사용자 지정 RUN_REAL_URL_PILOT=0에 따라 skip한다. local 안전성 adapter는 보수적 텍스트 패턴이며 의미 기반 moderation을 제공하지 않는 한계는 확정 계약에 기록했다.
