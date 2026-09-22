# ADR-019: production migration은 전용 계정·사전 복구 지점·배포 중단으로 보호한다

> 상태: **확정** · 날짜: 2026-09-20 · 영역: **server·database·operations**

## 맥락

Flyway migration은 CI/CD의 별도 단계에서 production Neon에 적용한다. 하지만 schema 또는 data 변경이 실패하거나 의도치 않은 결과를 내면, 실행 중인 API·Worker와 production data가 손상될 수 있다. runtime DB 계정이나 자동 rollback만으로는 이 위험을 충분히 줄일 수 없다.

## 결정

- CI/CD는 API·Worker runtime DB credential과 별도의 **migration 전용 Neon DB role·credential**을 사용한다. 이 credential은 migration 단계에만 사용할 수 있는 별도 Secret Manager secret으로 관리한다.
- production migration 전에는 CI에서 local·test DB 전체 migration 검증을 통과해야 한다.
- production 단계는 Flyway `validate`를 먼저 실행하고, 성공할 때만 `migrate`를 실행한다. migration 성공 후에만 API·Worker deployment를 진행한다.
- 일반적인 additive migration 전에는 Neon의 point-in-time recovery(PITR) 또는 restore history가 사용 가능한지 확인하고 배포 시각·migration version을 기록한다.
- destructive migration 또는 대량 data 변환 전에는 명시적으로 Neon branch 또는 snapshot을 생성해 복구 지점을 남기고, 그 식별자·생성 시각을 배포 기록에 남긴다.
- migration이 실패하면 CI/CD는 즉시 중단하고 API·Worker를 배포하지 않는다.
- 실패·오류가 발생한 migration은 자동 rollback하지 않는다. 호환 가능한 새 forward migration으로 수정하는 것을 기본으로 한다.
- 데이터 손상 가능성이 있으면 write 경로를 일시 중단하고, Neon restore branch에서 상태를 먼저 검증한 뒤 담당자가 수동 복구·전환을 결정한다.

## 이유와 trade-off

전용 migration role은 application runtime이 DDL 권한을 가져 schema를 우연히 바꾸는 위험을 줄인다. `validate → migrate → deploy` 순서는 새 코드가 없는 schema를 읽는 실패를 방지한다. Neon branch·snapshot과 PITR은 destructive change의 복구 지점을 제공한다.

명시적 branch·snapshot을 모든 배포에 만들면 관리·저장 비용이 늘 수 있으므로, 초기 MVP에서는 destructive 또는 대량 변환 migration에만 필수로 둔다. 일반 additive migration은 restore history 확인과 Git migration 이력으로 충분히 관리한다.

## 후속 결정

- 평균 월 기본 인프라 30,000원 상한을 위한 Cloud Billing budget·alert threshold, Cloud Run·Cloud Tasks·Neon 관측 지표와 대응 방식을 정한다.
- Worker request timeout과 Cloud Tasks task deadline은 extraction·LLM latency 부하 시험 결과로 정한다.
- CI/CD workflow의 세부 구현, Neon migration role 생성과 backup·restore runbook은 구현 계획에서 구체화한다.

## 근거

- [Neon Point-in-Time Restore](https://neon.com/blog/announcing-point-in-time-restore)
- [Neon branching workflows](https://neon.com/branching)
- [Flyway 문서](https://documentation.red-gate.com/fd)
