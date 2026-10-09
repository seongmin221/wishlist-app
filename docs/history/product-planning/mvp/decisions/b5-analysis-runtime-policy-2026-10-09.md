# 제품·운영 결정: B5 비동기 분석과 운영 복구

> 상태: **확정** · 날짜: 2026-10-09 · 영역: **제품·서버**

B5 착수 대조에서 남은 미결정 규칙을 사용자에게 묻고 확정했다. 이 문서는 결정을 기록하며 구현 완료를 의미하지 않는다. 설계는 [B5 spec](../../../../superpowers/specs/2026-10-09-b5-analysis-runtime-recovery-design.md)에서 관리한다.

## 결정 — 사용자 확인 완료

1. **재시도 예산은 generation 전체 합산.** general과 browser 실행을 합쳐 최대 3회이며 browser fallback 첫 실행도 1회로 센다. 30분은 generation의 가장 이른 첫 시도부터 잰다. general 3번째 시도에서 browser가 필요하다고 판정되면 남은 횟수가 없어 FAILED_RETRYABLE로 끝난다. [ADR-009](../../../architecture/server/ADR-009-outbox-retry-recovery.md)의 "실제 Worker 분석 실행 최대 3회"를 그대로 지킨다.
2. **metadata 선택.**
   - 가격이 범위(AggregateOffer)이거나 offer마다 다르면 price·currency는 null이다. 금액과 ISO 4217 통화가 둘 다 유효할 때만 원래 통화로 저장하고 환산하지 않는다.
   - merchant는 JSON-LD `offers.seller.name` → `og:site_name` 순서이며 둘 다 없으면 null이다. 도메인으로 채우지 않는다.
   - `metadataCheckedAt`은 general·browser가 대상 페이지를 읽어 metadata를 최종 반영한 시각이다. brand·가격이 null이어도 기록하고, 페이지를 얻지 못한 PARTIAL·실패에서는 기존 값을 유지한다.
3. **canonical.** 페이지 선언(`rel=canonical` → `og:url`)은 최종 URL과 같은 등록 도메인(eTLD+1)일 때만 채택한다. 그 외에는 정규화한 최종 URL을 쓴다. 정규화는 fragment와 승인된 tracking query만 제거하고 상품 식별 query는 보존한다.
4. **PENDING 정체 판정.** 발행된 PENDING은 마지막 변경 후 5분이 지나면 queue를 조회한다. 조회 실패 시 1분 뒤, task가 살아 있으면 5분 뒤 다시 검사한다. 살아 있는 task나 조회 장애는 재예약·상품 실패를 만들지 않는다.
5. **일반 lane DNS 실패는 Retryable.** generation 예산을 쓰며 소진 시 FAILED_RETRYABLE이다. 사설·loopback 주소, 허용하지 않는 scheme·port 차단은 FAILED_TERMINAL이다. browser lane의 대상 DNS 실패는 기존 확정대로 PARTIAL이다.

## 판단 근거

- 합산 3회는 외부 사이트 장애 때 비용 상한을 ADR대로 유지한다. fallback에 별도 보장 횟수를 주는 안과 lane별 3회 안은 상한을 넘거나 ADR 문구를 바꿔야 했다.
- 범위 가격을 최저가로 저장하면 '~부터' 의미를 표현할 필드가 없어 실제보다 싸 보일 수 있다. 신뢰할 값이 없으면 null이라는 B5 원칙을 따른다.
- 같은 등록 도메인 canonical은 모바일·데스크톱 주소를 모아 B9 중복 후보를 놓치지 않게 하고, 다른 판매처 상품과 합쳐지는 것을 막는다.
- 정체 5분은 delivery 105초·backoff 10/20초·Scheduler 1분을 더한 정상 지연을 넘는 첫 지점이다.
- JDK는 NXDOMAIN과 일시적 DNS 장애를 안정적으로 구분하지 못한다. 연결 실패와 같은 Retryable로 두면 일시 장애에서 회복할 수 있다.
