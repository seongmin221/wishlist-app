# B2 작업 수신과 카테고리 입력 기반

> 2026-10-07 · 입력 기반 선행 단계의 기록 · 후속 구현은 B2 구현·리뷰 이력을 참조

## 작업 공간·기준

사용자의 작업 소유권 이전 지시에 따라 독립 Orca 관리 worktree
`server-b2-category-management`, branch `seongmin221/server-b2-category-management`에서 시작했다.
최초 cwd·branch·HEAD·clean·AGENTS.md를 직접 확인했다.
HEAD와 로컬 origin/develop은 모두 `005772261fb6f4d5f32e5adb9489b841209dd9b3`이다.
기존 B1 worktree·핸드오프 파일은 변경하지 않았다. patch 이전은 없다.
GitHub issue 목록은 조회 당시 빈 배열이었다. issue 번호 없이 한글 커밋 convention을 사용한다.

## 문서 대조와 설계

[B2 계약·설계 초안](../../../architecture/server/category-management-api.md)과
[구현 계획](../../../superpowers/plans/2026-10-07-b2-category-management.md)을 작성했다.
BROWSE/SELECT, count·빈 custom, owner 복합 FK, 잠금 순서·AI snapshot 재검증을 구체화했다.

기존 TaxonomyCatalog는 공용 group 이름·leaf C-ID만 제공한다. 명시적 G001~G011 registry를
추가해 parent를 표시명과 분리했다. resource 배열 순서에서 parent ID를 매번 만들지 않는다.
schema seed·공개 CAT-01 연결은 다음 작업이다.

AI snapshot은 현재 ID/label만 저장하고 최종 적용은 item→job 순서다.
owner·custom version snapshot, 동일 connection 후보 공급, owner 선행 잠금은 후속 구현 대상이다.
생성 receipt 보존 기간, 진행 중 stale 실행 처리, 생성 rate limit 수치는 기존 문서의
당시 미확정 사항이므로 사용자에게 선택지를 제시했다. 후속 답변은 아래 정책 확정 기록에 반영했다.

## 독립 선행 구현

- CategoryInputPolicy: 이름40·설명200·예시5×60, Unicode code point 길이,
  빈 이름·제어문자·명시적 비표시/bidi 제어·잘못된 surrogate 거절. 정상 Unicode 결합문자는 보존한다.
  비교 key만 Unicode 공백 trim/collapse와
  Locale.ROOT lowercase를 적용하고 사용자 원문을 보존한다.
- PublicCategoryRegistry: G001~G011을 기존 group 이름에 명시적으로 고정하고 C001~C087 순서를 유지한다.
- 순수 생성/PATCH parser: primitive 타입 강제 변환 없이 입력을 검사한다.
  생략과 optional null을 구분하고 expectedVersion 양의 정수·parent 변경 거절을 검증한다.
  HTTP 등록·DB 저장은 아직 없다.

테스트를 먼저 추가했다. 첫 실행은 신규 입력/registry 타입 미구현으로 compileTestKotlin이
실패했다. parser 테스트도 신규 함수/타입 미구현 실패를 확인했다.
구현 후 단위 9개가 모두 통과했다. 이 결과는 HTTP/DB category 회귀 통과를 의미하지 않는다.

## 직접 실행한 baseline

server/에서 다음 명령을 새 worktree에서 직접 실행했다.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home DOCKER_HOST=unix:///Users/user/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 RUN_REAL_URL_PILOT=0 ./gradlew test --rerun-tasks
```

`BUILD SUCCESSFUL`, exit 0, 3분 44초. XML 집계는 tests=203, failures=0,
errors=0, skipped=1로 **202 통과·RealUrlPilot 1 skip**이다.
B1 이전 기록과 이 공간의 직접 실행 결과를 구분한다.

## 리뷰와 변경 후 검증

독립 리뷰에서 strict version 토큰과 정상 Unicode 결합문자 거절을 중요한 지적으로 받았다.
실제 회귀 10개 실행 중 두 테스트의 assertion 실패로 재현했다.
raw 양의 십진 정수 토큰 검사와 명시적 비표시/bidi 제어만 거절하는 방식으로 보완했다.
전체 group name→G-ID mapping을 모두 pin하는 테스트는 경미한 지적으로 기록했으며
Task 2 registry/DB seed 일치 검증에서 보강한다.

리뷰 회귀 실행이 보완 전 전체 실행과 겹쳐 Gradle binary 결과 파일이 충돌했다.
그 전체 실행은 `NoSuchFileException: in-progress-results-generic.bin`, exit 1로 실패했으며
통과 근거로 사용하지 않는다. 이후 targeted와 최종 전체 실행을 순차화한다.

보완 후 category/parser 단위 **11개 모두 통과**했다. 최종 코드를 대상으로 위 전체 명령을
단독으로 다시 실행해 `BUILD SUCCESSFUL`, exit 0, 3분 15초를 확인했다.
XML은 tests=214, failures=0, errors=0, skipped=1로 **213 통과·RealUrlPilot 1 skip**이다.
기존 B1/Worker 테스트도 이 최종 전체 실행에 포함됐다.

입력 기반·테스트는 로컬 `74b9d1d`로 커밋했다. 이 커밋은 공개 API 구현 완료가 아니다.

## 후속 정책 확정

세 정책은 사용자 확인을 완료했다. 계정 데이터 동안 receipt 보존, 미확정 stale 실행 예산 승계 재예약, owner별 60초 신규 성공 5건이다. V11·CAT-01~04·owner별 AI 후보·stale 전이의 후속 구현과 검증은 [B2 구현 기록](b2-category-implementation-2026-10-07.md)에 있다. 추가 리뷰에서 빈/공백 예시 422와 custom 축약 후에도 2,000 토큰을 넘으면 해당 호출의 공용 taxonomy 분류도 확정했다.
입력 선행 단계에서는 API inventory/implementation order를 준비 상태로 표시했다. 후속 구현 완료 상태와 전체 검증은 별도 B2 구현 이력으로 갱신한다. push·PR·병합은 하지 않는다.
