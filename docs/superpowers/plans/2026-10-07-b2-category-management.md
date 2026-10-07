# B2 카테고리 기본 관리 구현 계획

> **For agentic workers:** `superpowers:executing-plans`로 현재 Orca worktree에서 직접 구현한다.

**Goal:** CAT-01~04와 owner별 custom AI 후보·stale 재검증을 DB 경합까지 검증한다.

**Architecture:** Ktor/JDBC에 category package를 추가한다. app_users owner 잠금과 복합 FK로 격리·동시성을 보장한다. B1 공통 mapper·Worker fence를 유지한다.

**Tech Stack:** 기존 Kotlin/JDK17, Ktor, PostgreSQL16/Flyway, Testcontainers. dependency 변경 없음.

**Spec:** [B2 계약·설계 초안](../../architecture/server/category-management-api.md).

## Global Constraints

- 서버만 변경한다. V1~V10 수정, push/PR/merge, 다른 worktree 변경은 금지한다.
- 생성 key 보존·stale 처리·rate limit은 사용자 답변 전에 정책으로 확정하지 않는다.
- 공용 C001~C087 유지, custom 20개·이름40/설명200/예시5×60·같은 parent normalized unique.
- owner→category→item→job 순서. 외부 HTTP/AI 동안 DB 잠금 없음.
- B5 runtime·B7 사용자 재분석·B8 category 삭제를 구현하지 않는다.

## Review Focus

- Unicode 공백·code point 길이·제어문자·surrogate와 DB 제약 일치(Task 1/2).
- 여러 API instance의 동일 owner 동시 생성·replay·한도 보존(Task 2).
- owner 다른 category 참조의 HTTP/DB 차단(Task 2/3).
- snapshot staging 이후 category 편집의 최종 finish 재검증(Task 3).
- pool max=1 후보 공급에서 두 번째 connection 대기 방지(Task 3).

### Task 1: 입력 계약과 공용 parent registry

**Files:** `server/src/main/kotlin/app/category/CategoryInput.kt`, `PublicCategoryRegistry.kt`,
`app/http/CategoryRequestParser.kt`와 대응 테스트.

**Interfaces:** `CategoryInput(name:String, description:String?, examples:List<String>)`,
`CategoryInputPolicy.validate(input):Set<String>`, `normalizedName(name):String`.
registry는 기존 TaxonomyCatalog를 소비하고 명시적 G001~G011 ID·공용 leaf 순서를 생산한다.

- [x] 입력 경계·원문 보존·Unicode/중복 정규화·parent registry pin 테스트를 먼저 작성한다.
- [x] 단위 테스트 실행으로 미구현 실패를 확인한다.
- [x] 확정된 입력 정책과 parser를 구현한다. 정책 답변과 독립적인 작업이다.
- [x] 단위 테스트 실패/오류 0을 확인한다. 리뷰 보완 후 단위 11개 통과·전체 214개 중 213 통과/1 skip.

### Task 2: DB·서비스·CAT-01~04 HTTP

**Files:** `V11__category_management.sql`, `app/category/CategoryRepository.kt`, `CategoryService.kt`,
`app/persistence/OwnerStructureLock.kt`, `app/http/CategoryDtos.kt`, `CategoryRoutes.kt`, `Main.kt`,
`DatabaseMigrationTest.kt`, `CategoryServiceTest.kt`, `CategoryRoutesTest.kt`.

**Interfaces:** Task 1 입력·registry를 소비한다. service는 owner UUID를 필수로 받아
`list(owner,scope,parent)`, `get(owner,id)`, `create(owner,key,input)`, `patch(owner,id,expectedVersion,changes)`를 생산한다.
route는 SQL 없이 service snapshot과 공통 error/IO 경계를 사용한다.

- [ ] 사용자 답변을 spec에 반영하고 receipt·rate limit 계약을 고정한다.
- [ ] SELECT/BROWSE/count/empty custom·19개 동시 생성·normalized 중복·다른 parent·owner·version·key 회귀를 작성한다.
- [ ] 실제 PostgreSQL에서 미구현 실패를 확인한다.
- [ ] seed·custom/receipt 제약·owner 잠금·서비스·route/runtime을 구현한다.
- [ ] V10 upgrade, owner FK 직접 거절, 동시 replay, HTTP null/type/error/header까지 실행한다.
- [ ] 검증 후 기능 로컬 커밋을 남긴다. 실제 관련 issue 확인 결과와 한글 convention을 적용한다.

### Task 3: 표시명과 AI stale 보호

**Files:** `WishlistItemRepository.kt`, `WishlistItem.kt`, `WishlistItemViewMapper.kt`, `WishlistItemDtos.kt`,
`app/ai/CategoryCandidateProvider.kt`, `ClassificationSchema.kt`, `OpenAiResponsesGateway.kt`,
`AnalysisPendingResultRepository.kt`, `AnalysisResultRepository.kt`, `AnalysisWriteGuard.kt`,
`AnalysisClaimRepository.kt`, `AnalysisJobReconciler.kt`, `Main.kt`와 대응 회귀.

**Interfaces:** Task 2 custom/owner 잠금을 소비한다. 별도 custom_category_id와 public category_id를
공통 category.id로 합치고 owner-scoped 최신 표시명을 projection한다.
동일 connection 후보 공급은 owner·custom version snapshot을 생산하고 finish가 최종 검증한다.

- [ ] 후보 owner 격리·AI 제외·staging 이후 edit·양 lane stale·CONFIRMED/DEFERRED·USER·max=1 회귀를 작성한다.
- [ ] 보호 없는 현재 동작에서 실패를 확인한다.
- [ ] 사용자 답변에 따른 stale 처리·원자 job/outbox 전이를 구현한다. 기존 B1/Worker fence 유지.
- [ ] targeted DB 회귀와 기존 Worker/B1 회귀를 실행하고 기능/보완 커밋을 남긴다.

### Task 4: 전체 검증·리뷰·문서 마감

**Files:** `docs/architecture/server/` 계약·inventory·order·INDEX,
`docs/history/architecture/server/` 구현/검증 이력·INDEX.

- [ ] 사용자 지정 전체 명령을 server/에서 실행한다. 중단/미완료 실행을 통과로 기록하지 않는다.
- [ ] XML에서 tests/failures/errors/skipped를 집계하고 baseline과 구분한다.
- [ ] 전체 diff에서 lock 순서·FK·receipt·AI version·외부 호출·runtime 범위를 리뷰한다.
- [ ] 중요한 지적은 회귀 실패→보완→필요한 재검증 순서로 처리한다.
- [ ] 실제 구현 상태·검증·제품 답변·근거를 문서에 반영하고 문서 커밋을 분리한다.
- [ ] author/committer·clean 상태를 확인한다. push/PR/merge 없이 결과·남은 제한을 보고한다.

## 검증 명령

server/에서 사용자 지정 환경을 사용한다. 단위/targeted 실행은 같은 환경에 `--tests`를 붙인다.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home DOCKER_HOST=unix:///Users/user/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 RUN_REAL_URL_PILOT=0 ./gradlew test --rerun-tasks
```
