# B8 후보 이동과 영향 확인 삭제 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** PUR-07/08, CAT-05/06, PUR-05/06을 각각 검증·커밋한다.

**Architecture:** B7 owner 구조 잠금과 PurposeMembership을 재사용한다. query는 repeatable-read, command는 owner 잠금 이후 현재 상태를 검사하는 JDBC transaction이다. HTTP에는 기존 mapper·signer를 재사용한다.

**Tech Stack:** Kotlin/JVM 17, Ktor, JDBC, Flyway, PostgreSQL 16/Testcontainers, Gradle.

**Spec:** [설계](../specs/2026-10-24-b8-candidate-move-deletions-design.md)

## Global Constraints

- `server/b8-candidate-move-deletions` 유지, 한국어 커밋, 하위 묶음별 커밋.
- ACTIVE이면서 PROCESSING이 아닌 상품만 이동. 요청 1~100개, 중복 ID 불가.
- owner → 목적(SQL UUID ID순) → 상품(SQL UUID ID순) → job. 모든 쓰기는 단일 transaction.
- PurposeMembership은 상품별 transition; category/review를 목적 삭제 때문에 확정하지 않는다.
- DELETE JSON expectedVersion/impactToken, 상태 기반 무효화, 동일 성공 token replay 204, receipt 계정 수명 보존.
- C5/B9 이후 제외. 초기 필터·선택 유지는 C5 이관.

## Review Focus

- page 밖 상품 편집·새 연결: 전체 fingerprint가 바뀌어 삭제 409.
- 다른 owner item/resource/cursor: 정보 비노출 및 전체 rollback.
- replay 뒤 후속 이동/삭제: 저장 당시 성공 결과만 반환하고 새 이동 없음.
- Worker가 삭제 앞뒤에 finish: 사라진 참조·사용자 해제 재연결 없음.
- SQL UUID 순서와 B7 동시 변경: 교착 없이 membership/count/version 일치.

## Task 1 — B8a 후보 조회·원자 이동

**Files:**
- Create `server/src/main/kotlin/app/candidates/CandidateModels.kt`: B8 query/command/result/error.
- Create `server/src/main/kotlin/app/candidates/CandidateReadService.kt`: pool/filter/facets와 기존 item load.
- Create `server/src/main/kotlin/app/candidates/CandidateMoveService.kt`: bulk transaction.
- Create `server/src/main/kotlin/app/candidates/CandidateReceipts.kt`: 성공 결과 영속화.
- Create `server/src/main/resources/db/migration/V20__candidate_mutation_receipts.sql`.
- Create `server/src/main/kotlin/app/http/CandidateRoutes.kt`, `CandidateRequestParser.kt`, `CandidateCursorCodec.kt`.
- Modify `server/src/main/kotlin/app/Main.kt`: route 조립.
- Test `server/src/test/kotlin/app/candidates/CandidateMoveTest.kt`, `CandidateReadTest.kt`, `CandidateMoveConcurrencyTest.kt`, `server/src/test/kotlin/app/http/CandidateRoutesTest.kt`, `app/DatabaseMigrationTest.kt`.

**Interfaces:**
- `CandidateReadService.list(owner: UUID, purpose: UUID, filter: CandidateFilter, limit: Int, after: ReadPosition?, facets: Boolean): CandidatePage`.
- `CandidateMoveService.move(owner: UUID, purpose: UUID, key: UUID, request: CandidateMoveRequest): CandidateMoveResult`.
- `CandidateMoveRequest(expectedVersion: Int, items: List<MoveSelection>)`; `MoveSelection(id: UUID, expectedVersion: Int, expectedPurposeId: UUID?)`.
- `CandidateMoveResult(items: List<MovedItem>, purposes: List<MovedPurpose>, replayed: Boolean)`; receipt serializes result schema v1 without replayed flag. MovedItem=id/version/purposeId; MovedPurpose=id/candidateCount/membershipVersion. null 이전 목적은 목적 결과 목록에서 제외한다.
- receipt PK `(owner_id,operation,key)`, request_fingerprint, result_json JSONB schema v1. owner FK. 삭제 receipt는 Task 2의 V21에서 분리한다.
- `CandidateException(code, itemId?, currentVersion?)`: 존재·owner 오류 404, version/state/key 충돌 409, 입력 422.

- [ ] 상태/현재 목적 제외/이름 null/owner/filter/전체 facets/keyset 테스트를 먼저 작성하고 미정의 symbol RED를 확인한다.
- [ ] 공개 taxonomy registry와 custom parent를 사용해 pool을 SQL로 제한한다. facets는 같은 pool의 filter 적용 전 집계, query limit+1로 next를 계산한다.
- [ ] bulk 이동 테스트로 expectedPurposeId·item version·target version 충돌/한 항목 충돌 전체 rollback을 먼저 확인한다.
- [ ] owner lock 후 receipt 확인, 목적 SQL ID순 lock, 상품 SQL ID순 lock, 전체 검증, ItemChangePlan(Edit)·write·PurposeMembership 적용, schema v1 결과 저장을 구현한다. 새 요청의 이미 대상 소속은 409.
- [ ] replay(순서 무관 fingerprint, 다른 payload 409, 후속 이동 뒤 저장 결과), 동일 key 경합, B7 PATCH 경합, 반대 방향 bulk 경합을 실제 PostgreSQL로 검증한다.
- [ ] HTTP parser와 cursor RED 후 구현: strict keys/types/UUID/positive version/1~100/expectedPurposeId 필수 nullable, owner·purpose·filter cursor binding, 20/100, IO·취소·media 서명.
- [ ] migration upgrade와 route 공개 조립을 검증한다. 대상 Gradle `--tests 'app.candidates.*' --tests 'app.http.Candidate*' --tests 'app.DatabaseMigrationTest'` 실행.
- [ ] 계약/인계/INDEX 갱신 후 `feature(server): 목적 후보 조회와 원자 이동 구현` 커밋.

## Task 2 — B8b category 삭제

**Files:**
- Create `server/src/main/kotlin/app/candidates/DeletionImpactService.kt`, `StructureDeletionService.kt`.
- Create `server/src/main/resources/db/migration/V21__structure_deletion_receipts.sql`.
- Modify CandidateModels/Routes/RequestParser/CursorCodec, Main.
- Test `server/src/test/kotlin/app/candidates/CategoryDeletionTest.kt`, `StructureDeletionConcurrencyTest.kt`, HTTP·migration 테스트.

**Interfaces:**
- `DeletionKind { CATEGORY, PURPOSE }`.
- `DeletionImpactService.get(owner: UUID, kind: DeletionKind, id: UUID, limit: Int, after: ReadPosition?, expectedFingerprint: String?): StructureImpact`.
- `StructureImpact(version: Int, impactToken: String, affectedCount: Long, items: List<WishlistItem>, next: ReadPosition?)`.
- `StructureDeletionService.delete(owner: UUID, kind: DeletionKind, id: UUID, expectedVersion: Int, impactToken: String): Unit`.
- `structure_deletion_receipts` PK `(owner_id,kind,resource_id)`; expected_version/token, owner FK. 저장 token/version과 일치할 때만 replay.
- fingerprint canonical: v1/owner/kind/id/version/membershipVersion + ID순 전체 ACTIVE item id/version. token=SHA-256 hex. cursor도 fingerprint에 binding.

- [ ] 이름 null·빈 category·owner·archived 제외·page 밖 변경·새 연결·편집 RED 테스트를 추가한다.
- [ ] owner lock 후 metadata → 관련 purpose ID순 → 상품 ID순 잠금, fingerprint 재계산·불일치 409를 구현한다.
- [ ] category soft-delete, custom 참조/source 제거, CATEGORY override, 누락 사유/version/updated_at 갱신. review/purpose/analysis 보존.
- [ ] 같은 token/version만 204 replay, 다른 token/owner 404, 변경 시 409 HTTP/DB 검증.
- [ ] Worker finish 양 순서·stale job CANCELLED/FAILED_RETRYABLE·재분석 뒤 CATEGORY override 유지·HOME 보완/PATCH 재지정 검증.
- [ ] 대상 테스트 실행 후 계약·이력·INDEX 갱신, `feature(server): 영향 확인을 거쳐 카테고리 삭제` 커밋.

## Task 3 — B8c 목적만 삭제

**Files:** Task 2 공통 서비스/HTTP, `server/src/test/kotlin/app/candidates/PurposeDeletionTest.kt`와 경합 테스트.

**Interfaces:** Task 2의 kind PURPOSE. 동일한 impact/cursor/delete 입력, 오류 prefix PURPOSE.

- [ ] 빈 목적·owner 비공개·category/review 보존·USER+null·membership/activity RED 확인.
- [ ] purpose ID순 → item ID순 잠금 → fingerprint 재확인 → item USER+null/version 갱신 → 각 recordTransition → purpose DELETED/version 갱신 → receipt 저장.
- [ ] 이동/B7 편집/Worker와 삭제 경합·삭제 목적과 대체 목적 AI 자동 재연결 방지·replay 검증.
- [ ] PUR-05/06 HTTP와 B8a/b 회귀 실행. 계약·이력·INDEX 갱신 후 `feature(server): 영향 확인을 거쳐 목적만 삭제` 커밋.

## Task 4 — 전체 회귀와 인계

- [ ] host/container UTC 시각 확인 후 `caffeinate -i ./gradlew test` 실행.
- [ ] XML의 test/failure/error/skip 집계. opt-in skip·미실행 외부 검증 명시.
- [ ] spec/plan/계약/inventory/구현 순서/제품 결정/구현 이력·관련 INDEX 동기화.
- [ ] `git diff --check`, 상대 링크, scope 밖 변경 확인. 코드 리뷰·수정 범위 재검증.
- [ ] 검증 증거와 남은 C5/B10/B11 제약을 한국어로 보고한다.
