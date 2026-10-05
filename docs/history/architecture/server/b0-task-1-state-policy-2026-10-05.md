# B0 Task 1 — 상품 상태·허용 행동 정책

> 날짜: 2026-10-05 · 범위: B0 Task 1, 신규 HTTP API·DB migration 없음

## 브랜치와 기준

초기 서버 PR #1은 main에 병합됐고 `server/initial-setup@9eacba5`와 최신 `origin/main@4d31e3c`의 server 코드는 동일하다. 최신 main의 제품 문서 변경을 포함해 `server/b0-foundation`을 새로 만들었다. 기존 워크스페이스와 디자인 worktree를 유지했고 앞선 미커밋 계획 문서도 보존했다. 초기 코드의 병합은 production 준비 완료를 의미하지 않는다.

## 구현

`WishlistItemState`의 분석·검토·생명주기 축과 `WishlistItemPolicy.evaluate`를 추가했다. 순수 정책은 requiredAction·homeActionGroup·allowedActions를 반환한다. 기존 requiredAction 이름을 유지하고 category assignment/reassignment를 홈 정보 보완 영역으로 묶는다.

PROCESSING은 삭제만 가능하며 실패/PARTIAL 수동 완료, 재시도 가능한 실패의 재분석, READY 검토, 일반 편집을 구분한다. 수동 완료는 원래 분석 상태를 바꾸지 않고 재보완/재분석/검토를 막는다. 보류는 검토 영역에 자동 재노출하지 않으며 archived/deleted는 활성 상품 행동을 허용하지 않는다. 삭제 category는 PATCH 편집으로 재지정한다.

최신 main에 Task 1 관련 제품 문서 변경이 이미 통합되어 별도 복사/병합하지 않았다. 공통 상태/API 계약에 표시 그룹과 행동 정책을 추가했다. 현재 공개 API 응답에는 연결하지 않았으며 B0 나머지 Task와 B1에서 연결한다.

## 검증

정책 타입이 없는 상태에서 `WishlistItemPolicyTest` compile 실패를 확인한 후 구현했고, 동일 명령에서 14개 테스트가 통과했다. ARCHIVED/DELETED의 분석·검토 상태 조합, 이름 누락 우선순위, legacy 누락 사유, 수동 완료 뒤 category 삭제, 충분한 metadata가 있는 실패의 행동을 포함한다.

전체 suite도 실행했다. 81개 중 31개 기존 DB 테스트가 Flyway 연결 시 `Connection refused`로 실패했고 1개 live URL pilot은 opt-in skip이었다. 새 정책 테스트 14개에는 실패·skip이 없었다. Colima 시작 실패는 실행 VM이 없는 상태의 stale disk lock으로, 공식 unlock 후 Docker 28.4.0이 기동됐다. 테스트 process에는 Colima Docker 주소를 지정했고 시스템 Podman socket 설정은 변경하지 않았다.

컨테이너 내부 PostgreSQL readiness 로그와 호스트 포트 forwarding 준비 시점의 차이가 관찰됐지만 원인 확정·수정과 DB 회귀 완료는 후속 환경 검증에 남긴다. 127.0.0.1 host override 재검증에서도 기존 생성/HTTP DB 테스트가 실패했다. 임시 진단 PostgreSQL 컨테이너는 정리했다. DB 통합 회귀 완료는 보류하며 B0 전체 완료 기록이 아니다. 상태 정책 자체는 HTTP/DB 쓰기에 아직 연결되지 않아 이번 변경이 DB 접속 경로를 변경하지 않는다.

### 전체 회귀에서 실패한 기존 테스트


- `app.wishlist.CreateWishlistItemServiceTest :: unsafe url is rejected before any database write()`
- `app.wishlist.CreateWishlistItemServiceTest :: same key with another url is rejected without new work()`
- `app.wishlist.CreateWishlistItemServiceTest :: concurrent creates leave one item job and event()`
- `app.wishlist.CreateWishlistItemServiceTest :: same owner and key returns original item without a second job()`
- `app.analysis.GeneralWorkerServiceTest :: expired thirty minute deadline prevents another attempt()`
- `app.analysis.GeneralWorkerServiceTest :: claim revoked during processing cannot mark item ready()`
- `app.analysis.GeneralWorkerServiceTest :: expired running job is requeued with a new outbox task()`
- `app.analysis.GeneralWorkerServiceTest :: duplicate and deleted job are acknowledged without processing()`
- `app.extraction.GeneralExtractionProcessorTest :: blocked redirect is terminal without retry()`
- `app.extraction.GeneralExtractionProcessorTest :: complete extraction stores metadata before marking item ready()`
- `app.budget.LlmBudgetServiceTest :: response settlement releases unused reservation()`
- `app.budget.LlmBudgetServiceTest :: concurrent reservations cannot exceed either ceiling()`
- `app.budget.LlmBudgetServiceTest :: expired reserved releases while in flight settles maximum()`
- `app.budget.LlmBudgetServiceTest :: crossing eighty percent creates one durable alert per window()`
- `app.http.WishlistRoutesTest :: create replay conflict and invalid url use stable http contract()`
- `app.budget.BudgetAlertDispatcherTest :: failed notification stays pending and successful retry is delivered once()`
- `app.ai.AiClassificationServiceTest :: extraction is not ready until classification succeeds()`
- `app.ai.AiClassificationServiceTest :: invalid classification leaves extracted item partial()`
- `app.ai.AiClassificationServiceTest :: classification result remains provisional until worker commits terminal state()`
- `app.ai.AiClassificationServiceTest :: retry preserves human readable candidate labels()`
- `app.ai.AiClassificationServiceTest :: budget refusal makes item partial without calling model()`
- `app.http.LocalClassificationPathTest :: create API through general worker HTTP commits classified item ready()`
- `app.http.WorkerRoutesTest :: stale task is acknowledged with no content()`
- `app.http.WorkerRoutesTest :: stale browser task is acknowledged with no content()`
- `app.browser.BrowserRenderProcessorTest :: processor renders the source url belonging to its job()`
- `app.browser.BrowserWorkerServiceTest :: browser result writes metadata to the same item()`
- `app.browser.BrowserWorkerServiceTest :: blocked browser destination becomes partial without retry()`
- `app.browser.BrowserWorkerServiceTest :: revoked browser claim cannot write metadata or ready status()`
- `app.browser.BrowserWorkerServiceTest :: navigation timeout becomes partial without queue retry()`
- `app.browser.BrowserWorkerServiceTest :: infrastructure failure leaves browser claim for reconciler retry()`
- `app.browser.BrowserWorkerServiceTest :: needs browser stores stage flag and outbox together()`

추가로 Docker가 필요 없는 기존 테스트 24개와 새 정책 테스트 14개를 함께 실행해 **38개 통과, 실패/skip 0개**를 확인했다.
