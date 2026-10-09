# B5 비동기 분석·운영 복구 착수 대조

> 2026-10-09 · 문서·코드 대조 완료 · 정책 5건 확정 · spec 검토 대기 · 구현·테스트 미실행

## 기준과 범위

작업 브랜치는 `server/b5-analysis-runtime-recovery`다. HEAD는 origin/develop 기준 `abae37d`이다. [B5 구현 순서](../../../architecture/server/mvp-api-implementation-order.md#b5--비동기-분석과-운영-복구)에 따라 WORK-01 → WORK-02 → OPS-01을 진행한다. C4와 클라우드 IAM·Scheduler 실호출(B11)은 범위 밖이다.

## 현재 구현과 남은 연결

| 영역 | 현재 코드 | B5 작업 |
| --- | --- | --- |
| 일반 Worker | Main에 실행 역할·HTTP·추출·AI 후보 공급 연결 | metadata 확장·실패 구분·generation 전체 예산 |
| browser | 서비스·조건부 route·Playwright adapter 존재 | 독립 실행 역할·후보 공급·실제 연결 대상까지 제한하는 egress·남은 시간 공유 |
| 후보 보호 | owner별 custom/목적 snapshot과 공통 finish guard 존재 | 양쪽 runtime에서 stale·사용자 확정·이전 실행 보호 통합 검증 |
| 재시도 | lane별 횟수·최초 시각 검사, stale replacement는 두 lane 값을 승계 | stage/replacement에도 전체 3회·30분 보존 |
| metadata | 제목·설명·이미지·최종 URL만 추출/저장 | brand·price/currency·merchant·metadataCheckedAt의 provisional/final 저장·공통 조회 연결 |
| URL | 일반 HTTP는 검증한 DNS 주소에 연결, redirect 재검증 | DNS 장애와 차단 분리, canonical 정책; browser URL 검사만으로 실제 연결 IP 제한을 충족하지 못함 |
| outbox | 신규 event 지정 발행·batch·120초 lease·create RPC 5초 | backlog 후보별 실패 격리·진행 보장·Scheduler 복구 연결 |
| maintenance | RUNNING reconciler·budget 단발 CLI만 존재 | private HTTP 역할·오래된 PENDING·queue 조회·새 복구 identity·순환 검사 |

[PENDING 설계](../../../architecture/server/analysis-pending-recovery.md)의 queue 확인/재잠금·동시 복구 1건·미발행 기존 event 유지 규칙은 따른다. TaskGateway는 현재 create만 제공하므로 queue 조회 경계가 필요하다. 첫 batch의 실패/잠금이 뒤 후보 진행을 막는 문제도 계획에 포함한다.

## 이미 확정된 규칙

- 처리 80초 < Worker 90초 < task 105초 < lease 120초.
- Scheduler 1분 주기, queue 최대 3회·10초~600초 exponential backoff·30분. queue 정책을 다시 결정하지 않는다.
- 신뢰할 선택 metadata가 없으면 null. 금액은 원래 ISO 4217 통화로 보관하고 환산하지 않는다.
- browser 대상 사이트 차단·navigation timeout·대상 DNS/연결 실패는 PARTIAL, Worker 인프라 장애는 재시도한다.
- category stale는 미확정 작업만 예산 승계 replacement; 목적 stale는 판단 없음으로 기존 연결을 유지한다.
- B4 metadata null 경계를 B5 추출/저장으로 채운다. classified_at을 확인 시각으로 대체하지 않는다.
- local은 실제 PostgreSQL과 fake queue/AI로 검증한다. 실제 DB 테스트를 실행하지 못하면 통과로 처리하지 않는다.

## 확정한 규칙

재대조에서 초안의 질문 3건 외에 lane 간 재시도 횟수 계산과 일반 lane DNS 실패 의미도 미결정임을 확인했다. 5건 모두 [B5 제품·운영 결정](../../product-planning/mvp/decisions/b5-analysis-runtime-policy-2026-10-09.md)에 확정했다(합산 재시도·metadata 선택·canonical·PENDING 정체·DNS 실패). 설계는 [B5 spec](../../../superpowers/specs/2026-10-09-b5-analysis-runtime-recovery-design.md)에 있다.
