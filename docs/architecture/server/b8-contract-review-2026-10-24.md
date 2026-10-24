# B8 설계·계획·API 계약 검토

> 2026-10-24 · 구현 전 문서 검토 · 아래 네 항목을 설계·계획·API 계약에 반영함(구현 전)

대상: [설계](../../superpowers/specs/2026-10-24-b8-candidate-move-deletions-design.md), [계획](../../superpowers/plans/2026-10-24-b8-candidate-move-deletions.md), [API 계약](candidate-move-deletion-api.md).

원자 이동, owner 직렬화, 전체 영향 fingerprint, 저장 결과 replay, 사용자 목적 해제 보존은 세 문서에서 일관된다. 다음은 구현 전 보완할 P2 수준의 계약·검증 공백이다. 구현 결함을 실행으로 확인한 결과는 아니다.

## 1. 삭제 성공 receipt 조회 순서

설계의 최종 DELETE 순서는 자원 확인부터 시작하고, 계획 Task 2/3의 쓰기 절차에는 성공 receipt 조회가 명시되지 않았다. 기존 `CategoryRepository.find`와 `PurposeRepository.find`는 살아 있는 자원만 찾으므로 이 조회를 그대로 선행하면 성공한 삭제의 재전송도 404가 된다.

owner 잠금 → owner/kind/resource ID로 성공 receipt 조회 → token/version 일치 시 204 → 살아 있는 자원 조회 → 잠금/재검증/쓰기 순서를 명시한다. receipt가 있지만 입력이 다르면 이미 삭제된 자원이므로 404다. 실제 HTTP 재전송 테스트가 이 순서를 검증해야 한다.

## 2. 삭제와 분석 job의 단계별 검증

계획은 Worker finish 양 순서와 stale claim을 검증하지만, 삭제가 claim보다 먼저 일어나는 PENDING 경로와 Worker가 돌아오지 않는 lease 복구 경로를 구분하지 않는다.

기존 `AnalysisClaimRepository`는 claim 시점의 item version을 저장한다. 따라서 삭제 후 PENDING job이 claim되면 새 version으로 실행할 수 있으며, 삭제 전에 claim된 RUNNING job처럼 반드시 FAILED_RETRYABLE이 되는 것은 아니다. 이 경우에도 CATEGORY override 및 USER+null 목적이 보존되어야 한다. `AnalysisJobReconciler`는 stale RUNNING job의 lease 만료 시 취소와 FAILED_RETRYABLE 전이를 처리한다.

검증 행렬에 GENERAL/BROWSER PENDING → 삭제 → claim/finish, RUNNING → 삭제 → finish, RUNNING → 삭제 → lease 만료/복구를 추가한다. 분석 상태 보존은 삭제 transaction 자체의 동작이며 후속 Worker 전이와 구분해 API 문서에 설명한다.

## 3. 삭제 영향 목록의 정렬과 페이지 경계

영향 조회는 cursor와 ReadPosition을 사용하지만 목록 정렬 및 다음 페이지의 배타 경계가 정의되지 않았다. fingerprint의 ID 정렬은 hash canonicalization이며 화면 목록의 정렬을 정하지 않는다. 정렬과 cursor 조건이 다르면 안정된 영향 집합에서도 누락·중복이 생길 수 있다.

예를 들어 createdAt DESC,id DESC와 `(created_at,id) < (?,?)`를 계약·계획에 함께 명시한다. 같은 생성 시각, limit 변경, 마지막 페이지, 전체 페이지 합집합이 affectedCount와 일치하는지 검증한다.

## 4. 공개 응답·오류 형식의 확정

API 계약의 미지정 category 별도 count는 JSON 필드명이 없고, 기존 카드 표현은 `WishlistItemDto`인지 `ReadCardDto(item,anchorCursor)`인지 불명확하다. 새 CandidateException에는 itemId/currentVersion만 있어 B7처럼 잘못된 필드 details를 어떻게 전달할지도 정해져 있지 않다. 대상 목적 version 충돌·item 상태/소속 충돌·cursor fingerprint 충돌의 공개 code/details 역시 구현자 선택으로 남아 있다.

후보·영향 조회의 완전한 JSON 예시, 미지정 count 필드명과 위치, facet 순서, 필터 자원 없음의 상태 코드, 각 충돌의 code/details를 확정한다. 설계는 owner item 충돌 전반에 ID/version을 제시하지만 API는 version 충돌만이라고 좁히므로 이 차이도 맞춘다. 인증 후 다른 owner/없는 선택 항목과 owner 항목의 stale 충돌이 섞인 요청에서 어떤 오류가 우선하는지도 테스트로 고정한다.

## 검토 범위

관련 B7 mutation, repository, read DTO, analysis claim/finish/reconciler 코드를 대조했다. 구현·제품 정책은 변경하지 않았으며 실행 테스트는 수행하지 않았다.

## 반영 결과

- 삭제 절차를 owner 잠금 → 성공 receipt → 일치 시 204/불일치 404 → 살아 있는 자원 조회로 통일했다. category/purpose 모두 실제 삭제 뒤 상세 404와 DELETE replay 204를 검증하도록 계획했다.
- GENERAL/BROWSER × category/purpose에 PENDING 첫 claim·RUNNING stale finish·Worker 미복귀 lease 복구를 분리했다. 이후 명시적 재분석과 override/USER+null 보존도 검증한다.
- 영향 목록을 createdAt DESC,id DESC와 배타적 다음 경계로 고정했다. 같은 시각·limit 변경·끝 페이지·합집합/count 일치 검증을 명시했다.
- items를 WishlistItemDto 직접 배열로 고정하고 unassignedCategoryCount·facet key 생략/정렬, 완전한 JSON 예시와 code/details 표를 추가했다. 전체 선택 존재 검사를 충돌 검사보다 먼저 수행하고 item 충돌 전반의 ID/version 응답을 통일했다.

이번 수정은 문서 계약·검증 계획 변경이다. 위 테스트는 아직 구현하거나 실행하지 않았으며, 코드 결함이 해결됐다는 의미가 아니다.
