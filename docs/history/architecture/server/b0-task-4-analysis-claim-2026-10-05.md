# B0 Task 4 — 원자 claim과 공통 쓰기 보호

> 날짜: 2026-10-05 · 브랜치: `server/b0-foundation` · 기준: `d8150fd`

## 구현

AnalysisClaim은 job·item·DB owner·generation·새 execution token·lane·item version·lease를 전달한다. claim은 잠금 없는 job 관계 탐색 후 item→job 순서로 잠그고 상태를 재검증한다. 두 요청이 같은 PENDING job을 실행하려 해도 한 요청만 RUNNING으로 옮기며 lane별 attempt를 한 번 올린다.

현재 generation의 ACTIVE·PROCESSING·수동 미완료 상품만 claim한다. lane별 기존 3회·30분 제한을 유지하며 소진은 같은 transaction에서 FAILED_RETRYABLE과 상품 version 증가로 반영한다. 다른 generation/비활성 상품을 소진 처리하지 않는다. lease는 DB 시각부터 120초이고 assignment/failure 임시 값을 비운다. 일반 lane은 임시 metadata도 비우며 browser lane은 앞선 metadata를 유지한다. candidate snapshot은 보존한다.

공통 guard는 owner·item/job 관계·generation·token·RUNNING lane·lease·상품 상태·수동 변경·version을 확인한다. 모든 잠금 뒤 DB 시각을 읽어 잠금 대기 중 만료를 놓치지 않는다. 명시적 transaction을 요구하며 commit/rollback과 잠금 해제는 호출자가 맡는다. 무효 claim은 현재 token을 취소·수정하지 않는다. 상세 설계는 [상품 상태 저장 기반](../../../architecture/server/wishlist-state-persistence.md)을 따른다.

## 검증

새 타입/repository/guard 부재로 compileTestKotlin 실패를 확인한 뒤 구현했다. 관련 테스트 7개는 실제 PostgreSQL에서 동작한다.

- 일반/browser 두 lane에서 두 connection과 latch로 경합하여 승자 1개·Ignored 1개·attempt 1회·DB token/version/lease를 검증한다.
- 다른 generation, DELETED/ARCHIVED, 수동 완료, 비PROCESSING, 잘못된 lane/fallback은 Ignored이며 소진된 이전 job도 현재 상품을 바꾸지 않는다.
- 한 상품의 여러 generation 중 현재 job만 실행하고, 위조 owner/item/job/token/lane/version과 잘못된 job 관계는 guard를 통과하지 못한다.
- SQL로 lease·version·상태를 변경하여 무효화를 검증하며 guard 전후 job row가 동일하다.
- 재claim의 token 교체·임시 값 정리·browser metadata와 candidate snapshot 보존을 확인한다.
- lane별 횟수/시간 소진과 재호출 때 상품 version 중복 증가가 없음을 확인한다.
- guard가 잠근 item/job은 다른 connection의 쓰기가 lock timeout으로 실패하고, 호출자의 rollback 뒤에는 쓰기가 가능하다. autoCommit 사용은 거부한다.

최종 전체 회귀 `./gradlew test`는 102개 중 101개 통과, 실패·오류 0개, 실제 외부 URL pilot 1개 opt-in skip이었다. JDK 17과 Colima를 테스트 process에 지정했다. 별도 코드 검토에서 구체적인 지적 사항은 없었다.

## 다음 작업

Task 5에서 extraction/browser/AI/budget의 모든 중간 쓰기에 claim과 guard를 전달한다. 현재 Worker와 processor는 아직 새 claim 기반으로 연결하지 않았다. B0 전체 완료 기록이 아니다.
