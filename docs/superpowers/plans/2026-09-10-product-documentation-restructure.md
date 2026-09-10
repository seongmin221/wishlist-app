# Product Documentation Restructure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** 현재 유효한 제품 기획을 docs/product/의 사용자 흐름별 문서로 재구성하고, 기존 제품·기술 이력을 출시 범위와 관심사에 따라 docs/history/ 아래에 보존한다.

**Architecture:** docs/product/overview.md가 제품 범위와 전체 여정의 진입점이 되고, 네 개의 사용자 흐름 문서가 현재 상세 규칙을 하나씩 소유한다. 공통 상태와 taxonomy만 docs/product/references/로 분리하며, 과거 제품 기획은 docs/history/product-planning/mvp/, 기술 ADR은 docs/history/architecture/에서 보존한다.

**Tech Stack:** Markdown, Git, rg, Ruby 표준 라이브러리를 이용한 상대 링크 검사

**Spec:** docs/history/PRODUCT-DOCUMENTATION-STRUCTURE.md — Task 1에서 docs/history/architecture/repository/product-documentation-structure.md로 이동한다.

## Global Constraints

- 현재 제품 동작의 단일 기준은 docs/product/이다.
- 미래 기능은 구체적인 기획이 시작되기 전까지 docs/product/overview.md의 향후 범위에만 둔다.
- 동일한 현재 규칙은 product 문서 하나만 소유하고 다른 문서는 링크로 참조한다.
- API, 데이터베이스, 플랫폼별 구현 상세는 docs/architecture/에 둔다.
- 기존 history 본문과 Git 이력은 보존하며 상태, 대체 관계, 현재 문서 링크만 필요한 만큼 수정한다.
- 제품 기획 history 버전은 세션이나 문서 개정이 아니라 mvp, v1, v2 같은 제품 출시 범위를 뜻한다.
- 기존 ADR 번호를 유지한다.
- 날짜, draft, 출시 버전은 현재 product 파일명에 넣지 않는다.
- 여러 문서나 하위 주제를 가진 폴더에는 간결한 INDEX.md를 둔다.
- 작업 전부터 존재하는 AGENTS.md, .codex/, .superpowers/ 변경은 수정하거나 커밋하지 않는다.
- git add .를 사용하지 않고 각 Task에서 바꾼 파일만 명시적으로 stage한다.
- 커밋은 docs: 한글 설명 형식의 제목과 간결한 한글 본문을 사용한다.
- 경로와 링크 변경이 순차 의존하므로 Task를 병렬 실행하지 않는다.

---

### Task 1: 기술 결정 history를 관심사별 구조로 이동

**Files:**
- Create: docs/history/architecture/INDEX.md
- Create: docs/history/architecture/repository/INDEX.md
- Move: docs/history/ADR-001-documentation-recording.md → docs/history/architecture/repository/ADR-001-documentation-recording.md
- Move: docs/history/ADR-004-repository-structure.md → docs/history/architecture/repository/ADR-004-repository-structure.md
- Move: docs/history/PRODUCT-DOCUMENTATION-STRUCTURE.md → docs/history/architecture/repository/product-documentation-structure.md
- Create: docs/history/architecture/client/INDEX.md
- Create: docs/history/architecture/server/INDEX.md
- Move: docs/history/server/ADR-002-kotlin-server-language.md → docs/history/architecture/server/ADR-002-kotlin-server-language.md
- Move: docs/history/server/ADR-003-select-ktor.md → docs/history/architecture/server/ADR-003-select-ktor.md
- Create: docs/history/architecture/ai/INDEX.md
- Delete after consolidation: docs/history/client/README.md, docs/history/server/README.md, docs/history/server/INDEX.md, docs/history/ai/README.md
- Modify: docs/history/INDEX.md, docs/history/README.md, docs/README.md
- Modify: this plan only to replace the Spec path with its final path
- Modify: every Markdown link consumer found in Step 2

**Interfaces:**
- Consumes: approved hierarchy and existing ADR numbers.
- Produces: stable history/architecture paths used by later documents.

- [ ] **Step 1: Capture the pre-existing worktree state**

Run:

~~~bash
git status --short
git diff -- AGENTS.md
~~~

Expected: record user-owned changes; do not stage or edit them.

- [ ] **Step 2: Inventory links that will break**

Run:

~~~bash
rg -n 'history/(ADR-|server/ADR-|client/README|server/(README|INDEX)|ai/README)|\((ADR-|server/ADR-)' docs --glob '*.md'
~~~

Expected: a finite list of link consumers to update in Step 5.

- [ ] **Step 3: Create target indexes and move files**

Write concise scope indexes. Preserve useful text from old platform README files. Move the ADRs and design spec, keeping ADR contents and numbers intact. Update relative links changed by depth.

- [ ] **Step 4: Update every link consumer and the plan Spec path**

Do not leave compatibility stubs at old paths. Update docs/history/INDEX.md and README.md so technical history is no longer mixed with product-planning history.

- [ ] **Step 5: Verify legacy paths and local links**

Run the Step 2 search again. Expected: no old Markdown path.

Run:

~~~bash
ruby -e 'bad=[]; Dir["docs/**/*.md"].each{|f| File.read(f).scan(/\[[^\]]*\]\(([^)]+)\)/).flatten.each{|u| next if u =~ /^(https?:|#)/; p=File.expand_path(u.split("#",2)[0],File.dirname(f)); bad << "#{f}: #{u}" unless File.exist?(p)}}; abort bad.join("\n") unless bad.empty?; puts "all local links resolve"'
~~~

Expected: all local links resolve.

- [ ] **Step 6: Commit only this migration**

Stage only moved architecture-history files, their indexes, and link consumers. Check git diff --cached --check and git diff --cached --name-only, then commit:

~~~text
docs: 기술 결정 이력 구조 정리

repository, client, server, AI 기술 결정을 관심사별 history 경로로 이동하고 관련 링크를 갱신한다.
~~~

---

### Task 2: MVP 제품 기획 history를 출시 범위 아래로 이동

**Files:**
- Create: docs/history/product-planning/INDEX.md
- Create: docs/history/product-planning/mvp/INDEX.md
- Create: docs/history/product-planning/mvp/checkpoints/INDEX.md
- Create: docs/history/product-planning/mvp/decisions/INDEX.md
- Move: four existing checkpoint files according to the table below
- Move: thirteen existing decision and direction files according to the table below
- Modify: docs/history/INDEX.md, docs/history/README.md, and every link consumer found in Step 1

**Interfaces:**
- Consumes: existing checkpoint metadata, decision status, dates, bodies, and cross-links.
- Produces: release-scoped history paths used by all product documents.

Checkpoint mapping:

| Existing file | Target filename |
| --- | --- |
| PRODUCT-PLANNING-CHECKPOINT-2026-08-29.md | checkpoints/initial-direction.md |
| PRODUCT-PLANNING-CHECKPOINT-2026-08-29-PURPOSE-REVIEW.md | checkpoints/purpose-review.md |
| PRODUCT-PLANNING-CHECKPOINT-2026-09-04.md | checkpoints/taxonomy-and-purpose.md |
| PRODUCT-PLANNING-CHECKPOINT-2026-09-05.md | checkpoints/policy-completion.md |

Decision mapping:

| Existing suffix | Target filename |
| --- | --- |
| PURPOSE-GROUPS | decisions/purpose-groups.md |
| AI-PURPOSE-LINKING | decisions/ai-purpose-linking.md |
| ARCHIVE-DELETE-SAFEGUARDS | decisions/archive-delete-safeguards.md |
| CUSTOM-CATEGORY-LIFECYCLE | decisions/custom-category-lifecycle.md |
| CUSTOM-CATEGORY-SAFETY | decisions/custom-category-safety.md |
| DUPLICATE-ITEMS | decisions/duplicate-items.md |
| PRODUCT-CACHE-SNAPSHOT | decisions/product-cache-snapshot.md |
| PURPOSE-DELETION | decisions/purpose-deletion.md |
| WEBVIEW-BEHAVIOR | decisions/webview-behavior.md |
| HOME-ACTION-PRIORITY | decisions/home-action-priority.md |
| LOCAL-PENDING-ANALYSIS | decisions/local-pending-analysis.md |
| MANUAL-COMPLETION | decisions/manual-completion.md |
| PRODUCT-TAXONOMY-DIRECTION | decisions/taxonomy-direction.md |

- [ ] **Step 1: Inventory files and inbound links**

Run:

~~~bash
find docs/history -maxdepth 1 -type f -name 'PRODUCT-*' | sort
rg -n 'PRODUCT-(PLANNING|TAXONOMY)' docs --glob '*.md'
~~~

Expected: four checkpoints and thirteen decisions/direction records plus their inbound links.

- [ ] **Step 2: Create release and record-type indexes**

product-planning/INDEX.md defines release-scope versioning. mvp/INDEX.md summarizes the MVP planning sequence and links the current product index. The checkpoint index is chronological; the decision index groups records by the four user flows.

- [ ] **Step 3: Move checkpoints without merging bodies**

Apply the table exactly. Change a stale 진행 중 status to 대체됨 only because current product docs replace it as the resume point. Preserve dates, session context, and decisions.

- [ ] **Step 4: Move decisions and repair links**

Apply the decision table exactly. Preserve status and date metadata. Update cross-links and every inbound link found in Step 1. Links to product-spec.md and product-taxonomy-draft.md remain until later tasks.

- [ ] **Step 5: Verify mapping counts**

Run:

~~~bash
find docs/history/product-planning/mvp/checkpoints -maxdepth 1 -type f ! -name INDEX.md
find docs/history/product-planning/mvp/decisions -maxdepth 1 -type f ! -name INDEX.md
find docs/history -maxdepth 1 -type f -name 'PRODUCT-*'
~~~

Expected: 4 checkpoint files, 13 decision files, and no root PRODUCT files. Run the full link checker from Task 1.

- [ ] **Step 6: Commit only product-planning history**

Verify staged names exclude unrelated files, then commit:

~~~text
docs: MVP 제품 기획 이력 구조 정리

기획 체크포인트와 결정 기록을 MVP 출시 범위 아래로 이동하고 역할별 index와 대체 관계를 정리한다.
~~~

---

### Task 3: Product reference와 탐색 골격 구성

**Files:**
- Create: docs/product/README.md
- Create: docs/product/INDEX.md
- Create: docs/product/references/INDEX.md
- Create: docs/product/references/item-states.md
- Move: docs/product-taxonomy-draft.md → docs/product/references/product-taxonomy.md
- Modify: docs/product-spec.md and moved history files that link taxonomy

**Interfaces:**
- Consumes: existing 11 top-level categories, 87 leaf types, and state terms in current product/history docs.
- Produces: canonical taxonomy and state vocabulary for all four user-flow documents.

- [ ] **Step 1: Record taxonomy and state baselines**

Run:

~~~bash
rg -c '^## ' docs/product-taxonomy-draft.md
rg -c '^- ' docs/product-taxonomy-draft.md
rg -n 'PROCESSING|READY|PARTIAL|FAILED|NEW|CONFIRMED|UNCONFIRMED|분석 대기|분류 중|정보 보완 필요|카테고리 미지정|분류·목적 확인|재시도 가능' docs/product-spec.md docs/history/product-planning/mvp
~~~

Expected: taxonomy counts are 11 and 87; state inventory is available for comparison.

- [ ] **Step 2: Create README and indexes**

README defines current-product authority, the common document template, the single-owner rule, and reference promotion criteria. INDEX names overview, four flows, and references, but does not create broken Markdown links to flow documents before they exist.

- [ ] **Step 3: Move taxonomy without changing entries**

Change draft wording to launch-baseline wording. Preserve all category headings and leaf bullets; update introduction and links only.

- [ ] **Step 4: Create item-states.md from proven terms**

Separate internal processing states, user-visible action areas, review state, and completion conditions. Do not invent a unified state machine if current documents do not define one. Link behavioral ownership to history until the feature docs exist.

- [ ] **Step 5: Update inbound taxonomy links and verify**

Expected after checks: new taxonomy still has 11 second-level headings and 87 bullet entries; docs/product-taxonomy-draft.md is absent; rg for product-taxonomy-draft.md finds no Markdown link. Run the full link checker.

- [ ] **Step 6: Commit the reference structure**

Commit:

~~~text
docs: 제품 공통 참조 문서 구성

제품 문서 탐색 골격을 만들고 상품 taxonomy와 공통 상태 정의를 reference 문서로 분리한다.
~~~

---

### Task 4: 상품 저장 사용자 흐름 문서 작성

**Files:**
- Create: docs/product/save-a-product.md
- Modify: docs/product/INDEX.md
- Modify: docs/product/references/item-states.md
- Modify: related history decision links

**Interfaces:**
- Consumes: local-pending-analysis.md, product-cache-snapshot.md, duplicate-items.md, current product-spec.md, and server extraction architecture.
- Produces: one owner for share reception, local pending, server submission, analysis, cache reuse, retry, and deletion during processing.

- [ ] **Step 1: Build a source checklist**

Read all sources completely. Classify every saving rule as precondition, happy path, state, account ownership, failure/recovery, deletion/race handling, organizing handoff, or technical boundary.

- [ ] **Step 2: Write goal, principles, scope, and normal flow**

State that reliable capture precedes analysis and saving does not wait for extraction. Cover unauthenticated storage, logged-in offline ownership, login, network recovery, server creation, and cross-device visibility.

- [ ] **Step 3: Write cache, retry, and deletion behavior**

Cover READY cache eligibility and seven-day TTL, WishlistItem snapshot semantics, PARTIAL/FAILED handling, user retry, manual-completion handoff, deletion while processing, late results, and re-sharing deleted URLs.

- [ ] **Step 4: Establish cross-flow boundaries**

Duplicate detection may originate here, but user resolution belongs to organize-candidates.md. Direct completion belongs to inspect-and-edit-a-product.md. Shared vocabulary belongs to item-states.md.

- [ ] **Step 5: Verify coverage and links**

Search both sources and target for 로그인, 오프라인, 로컬, 7일, READY, PARTIAL, FAILED, 재시도, 삭제, 늦게. Check each source rule has one owning paragraph and no new rule was invented. Run the full link checker.

- [ ] **Step 6: Commit**

~~~text
docs: 상품 저장 흐름 기획 분리

공유 수신부터 로컬 대기, 서버 분석, 실패 복구까지의 현재 제품 규칙을 하나의 사용자 흐름으로 정리한다.
~~~

---

### Task 5: 구매 후보 정리 사용자 흐름 문서 작성

**Files:**
- Create: docs/product/organize-candidates.md
- Modify: docs/product/INDEX.md
- Modify: docs/product/references/item-states.md
- Modify: related history decision links

**Interfaces:**
- Consumes: taxonomy reference and purpose-groups, purpose-deletion, custom-category-lifecycle, custom-category-safety, ai-purpose-linking, duplicate-items, taxonomy-direction.
- Produces: one owner for classification, purpose grouping, review, and duplicate resolution.

- [ ] **Step 1: Build a source checklist**

Read all source records and matching product-spec sections. Sort rules into taxonomy, custom categories, purposes, AI decisions, review, duplicates, and resolution.

- [ ] **Step 2: Write goal, principles, and category flows**

Preserve the distinction between what a product is and why it is compared. Cover public taxonomy immutability; custom creation limits, normalization, edit/delete/reassignment, archive snapshots, request rejection, and internal AI-candidate exclusion.

- [ ] **Step 3: Write purpose and AI flows**

Cover purpose creation, optional connection, deletion effects, confirmed-unassigned state, evidence requirements, re-evaluation limits, and user decisions overriding AI.

- [ ] **Step 4: Write review and duplicate flows**

Cover confirm/defer behavior and accessible buttons; normal-list visibility; PROCESSING, READY, PARTIAL, FAILED candidate behavior; new-item deletion, existing-item deletion, and keep-both choices.

- [ ] **Step 5: Verify exact limits and links**

Search for 20개, 40자, 200자, 5개, 60자, 확정된 목적 미지정, 오른쪽, 왼쪽, PROCESSING, READY, PARTIAL, FAILED, 모두 유지. Confirm no conflicting ownership in the new product tree. Run the full link checker.

- [ ] **Step 6: Commit**

~~~text
docs: 구매 후보 정리 흐름 기획 분리

카테고리, 목적, AI 연결, 중복 검토 규칙을 후보 정리 사용자 흐름으로 통합한다.
~~~

---

### Task 6: 상품 확인·편집 사용자 흐름 문서 작성

**Files:**
- Create: docs/product/inspect-and-edit-a-product.md
- Modify: docs/product/INDEX.md
- Modify: docs/product/references/item-states.md
- Modify: related history decision links

**Interfaces:**
- Consumes: manual-completion, home-action-priority, webview-behavior, custom-category-lifecycle, archive-delete-safeguards, and current list/edit/webview sections.
- Produces: one owner for home/list visibility, information completion, editing, web navigation, and active-item deletion.

- [ ] **Step 1: Build a source checklist**

Separate visibility priority, normal-list inclusion, missing information, manual completion, editable fields, webview navigation, process restoration, and active deletion.

- [ ] **Step 2: Write visibility and completion behavior**

Document the single-action-area invariant and exact priority: 분석 대기 → 분류 중 → 정보 보완 필요 → 카테고리 미지정 → 분류·목적 확인. Keep normal-list inclusion separate. Require product name and category; keep image, price, currency, merchant, brand, and purpose optional.

- [ ] **Step 3: Write edit and webview behavior**

Cover default image, manual-completion terminal behavior, retained diagnostics, unified edit screen, all product states, controls, page history, new-window requests, external apps, cookies, return behavior, process death, and no automatic purchase detection.

- [ ] **Step 4: Write active deletion behavior**

Cover confirmation, purpose impact, no undo, no archive effect, and purpose-unassigned items. Link saving for deletion during analysis and finishing for archive-record deletion.

- [ ] **Step 5: Verify coverage and links**

Search for all five action-area labels plus 제품명, 카테고리, 기본 이미지, 쿠키, 새 창, 외부 앱, 프로세스, 결제, 실행 취소. Confirm every existing rule appears once under the correct section. Run the full link checker.

- [ ] **Step 6: Commit**

~~~text
docs: 상품 확인과 편집 흐름 기획 분리

홈 노출, 정보 보완, 상품 편집, 웹뷰와 활성 상품 삭제 규칙을 사용자 흐름으로 정리한다.
~~~

---

### Task 7: 구매 결정 종료 사용자 흐름 문서 작성

**Files:**
- Create: docs/product/finish-a-purchase-decision.md
- Modify: docs/product/INDEX.md
- Modify: related history decision links

**Interfaces:**
- Consumes: archive-delete-safeguards, purpose-deletion, custom-category-lifecycle, and current purchase/archive rules.
- Produces: one owner for optional purchase selection, archive creation, snapshots, restoration, and archive deletion.

- [ ] **Step 1: Build a source checklist**

List archive eligibility, purchased-item selection, confirmation, snapshot fields, title, display, restoration, and deletion.

- [ ] **Step 2: Write archive and snapshot flows**

State that ending comparison and recording a purchase are related but distinct: purchase selection is optional, while a non-empty purpose and all candidates are archived together. Cover snapshots, default title, title editing, and independence from active data.

- [ ] **Step 3: Write restoration and deletion flows**

Cover detail-only cancellation, atomic restoration of the purpose and all items, removal of purchase designation, no individual restore/delete, whole-record deletion, and no undo.

- [ ] **Step 4: Verify archive invariants and links**

Search for 빈 목적, 선택, 스냅샷, 기본 제목, 상세 화면, 전체, 개별, 실행 취소. Confirm there is no partial-restore interpretation. Run the full link checker.

- [ ] **Step 5: Commit**

~~~text
docs: 구매 결정 종료 흐름 기획 분리

구매 상품 지정, 목적 아카이브, 복원과 기록 삭제 규칙을 하나의 사용자 흐름으로 정리한다.
~~~

---

### Task 8: Product overview로 기준 문서 전환

**Files:**
- Create: docs/product/overview.md
- Delete after migration: docs/product-spec.md
- Modify: docs/product/INDEX.md, docs/product/README.md
- Modify: docs/INDEX.md, docs/README.md
- Modify: docs/history/INDEX.md, docs/history/README.md
- Modify: all architecture, learning, and history files linking product-spec.md

**Interfaces:**
- Consumes: completed four flow documents and references.
- Produces: one concise product entry point and repository-wide cutover from the legacy spec.

Required source mapping:

| Existing content | Destination |
| --- | --- |
| product purpose, MVP scope, core journey | product/overview.md |
| future scope | product/overview.md only |
| taxonomy | product/references/product-taxonomy.md and organizing link |
| saving, analysis, cache, retry | product/save-a-product.md |
| category, purpose, duplicate, review | product/organize-candidates.md |
| list, completion, editing, webview, active deletion | product/inspect-and-edit-a-product.md |
| purchase, archive, restore, archive deletion | product/finish-a-purchase-decision.md |
| SSRF, worker idempotency, provider/auth/queue/hosting/notification choices | server architecture |

- [ ] **Step 1: Check every product-spec section against the mapping**

Create a temporary review checklist outside the repository or in the working notes. Do not delete the old spec until every section has a destination.

- [ ] **Step 2: Write the concise overview**

Include purpose, principles, MVP included/excluded scope, one linked end-to-end journey, future scope, and only product-level unresolved matters. Do not repeat detailed rules.

- [ ] **Step 3: Preserve technical requirements in architecture**

Ensure SSRF points to extraction-pipeline.md, worker idempotency is explicit in server architecture, and provider/auth/queue/hosting/notification choices remain architecture open decisions.

- [ ] **Step 4: Update repository entry points and inbound links**

docs/INDEX.md and docs/README.md lead to product/INDEX.md and overview.md. History says it is not the current product source. Architecture and learning link to the most specific product owner.

- [ ] **Step 5: Remove legacy entry files and verify**

Delete product-spec.md only after mapping and links are complete. Confirm product-spec.md, product-taxonomy-draft.md, and root history/PRODUCT-* Markdown links no longer exist. Historical prose may mention an old name, but it must not link to an old path. Run the full link checker.

- [ ] **Step 6: Commit**

~~~text
docs: 제품 기획 기준 문서 전환

기존 통합 스펙을 간결한 product overview로 전환하고 모든 문서 진입점과 참조를 새 구조에 맞춘다.
~~~

---

### Task 9: 전체 내용 보존과 구조 일관성 검증

**Files:**
- Modify only when a verified defect exists: the smallest owning product, history, architecture, or index file
- Do not create: redirect stubs, empty v1/v2 folders, placeholder future-feature documents

**Interfaces:**
- Consumes: complete migrated documentation.
- Produces: verified current-product authority, preserved history, and valid navigation.

- [ ] **Step 1: Verify required structure**

Confirm all of these exist:

~~~text
docs/product/overview.md
docs/product/save-a-product.md
docs/product/organize-candidates.md
docs/product/inspect-and-edit-a-product.md
docs/product/finish-a-purchase-decision.md
docs/product/references/item-states.md
docs/product/references/product-taxonomy.md
docs/history/product-planning/mvp/INDEX.md
docs/history/architecture/INDEX.md
~~~

Confirm docs/history/product-planning/v1 and v2 do not yet exist.

- [ ] **Step 2: Run count regressions**

Expected: taxonomy has 11 second-level headings and 87 bullets; MVP history has 4 checkpoint files and 13 decision files excluding indexes.

- [ ] **Step 3: Run repository-wide link and legacy-path checks**

Run the full Ruby link checker. Then run:

~~~bash
rg -n '\]\([^)]*(product-spec\.md|product-taxonomy-draft\.md|history/PRODUCT-|history/ADR-|history/server/ADR-)' docs --glob '*.md'
~~~

Expected: no output.

- [ ] **Step 4: Review single ownership manually**

For every rule from the Task 8 mapping, identify exactly one owning product document. Pay special attention to duplicate resolution, manual completion, deletion during analysis, active deletion, and archive deletion.

- [ ] **Step 5: Review history preservation**

Use git log --follow on initial-direction.md and ADR-003-select-ktor.md. Diff the pre-migration commit against HEAD for history paths. Expected: path, link, index, and explicit status changes without lost decisions.

- [ ] **Step 6: Confirm unrelated state is untouched**

Run git status --short and git diff -- AGENTS.md. Compare with Task 1 baseline. Expected: pre-existing AGENTS.md, .codex/, and .superpowers/ state is unchanged.

- [ ] **Step 7: Fix only verified defects**

If a check fails, edit only the owning document, rerun that check and the full link checker, then commit:

~~~text
docs: 제품 문서 구조 검증 보완

최종 검증에서 확인한 링크, 소유 경계 또는 이력 보존 문제를 바로잡는다.
~~~

If no defect exists, do not create an empty commit.
