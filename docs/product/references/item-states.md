# 상품 상태 용어

> 상태: **제안** · 최종 갱신: 2026-09-13 · 근거: 이전 통합 스펙의 traceability와 연결된 MVP 결정 기록

이 문서는 여러 흐름에서 쓰는 상태 용어를 구분한다. 용어를 한 순서의 단일 상태 기계로 합치지 않으며, 각 상태를 만드는 행동과 예외 처리는 해당 사용자 흐름 문서가 소유한다.

## 내부 처리 상태

- `로컬 대기` — 서버 전송 전 기기에만 보관된 항목을 가리킨다.
- `PROCESSING` — 서버의 상품 정보 추출이 진행 중인 항목을 가리킨다.
- `READY` — 충분한 상품 정보 추출 결과를 가리키며, 캐시 재사용 판단에 쓰일 수 있다.
- `PARTIAL` — 추출은 끝났지만 일부 정보가 누락된 결과를 가리킨다.
- `FAILED_RETRYABLE` — 내부 자동 재시도를 소진한 일시적 실패로, 사용자가 직접 보완하기 전 다시 분석을 요청할 수 있다.
- `FAILED_TERMINAL` — 반복해도 성공하기 어려운 실패로, 재분석 대신 안전한 실패 이유와 직접 보완·삭제를 제공한다.

저장·분석·재시도와 삭제 결과는 [상품 저장](../save-a-product.md)이 소유한다. 캐시의 `READY`·`PARTIAL`·실패 구분도 이 흐름에서 적용하며, 결정 근거는 [로컬 대기 저장과 분석 재시도 결정](../../history/product-planning/mvp/decisions/local-pending-analysis.md) 및 [Product 캐시와 WishlistItem 스냅샷 결정](../../history/product-planning/mvp/decisions/product-cache-snapshot.md)에 보존한다.

## 사용자 조치 영역

홈의 조치 필요 영역은 내부 처리 상태와 같은 축이 아니다. 현재 확인된 영역은 `분석 대기`, `분류 중`, `정보 보완 필요`, `카테고리 미지정`, `분류·목적 확인`이다. 한 항목을 어느 영역에 보일지와 우선순위는 [상품 확인과 편집](../inspect-and-edit-a-product.md)이 소유하고, 결정 근거는 [홈 조치 필요 영역의 우선순위](../../history/product-planning/mvp/decisions/home-action-priority.md)에 보존한다.

## 검토 상태

`분류·목적 확인`은 자동 카테고리·목적 연결 또는 중복 후보를 사용자가 검토하는 영역이다. `확정`·`보류`, 접근 가능한 조작, 미확정 표시와 자동 재노출의 상세는 [구매 후보 정리](../organize-candidates.md)가 소유한다. 이 동작의 결정 근거는 [taxonomy와 목적 체크포인트](../../history/product-planning/mvp/checkpoints/taxonomy-and-purpose.md) 및 [중복 URL과 상품 후보 처리 결정](../../history/product-planning/mvp/decisions/duplicate-items.md)에 보존한다.

## 완료 조건

일반 카테고리 목록에서 상품을 사용하기 위한 최소 정보는 제품명과 카테고리다. 대표 이미지, 가격, 통화, 판매처, 브랜드와 목적은 선택 정보다. `PARTIAL` 또는 분석 실패 결과라도 사용자가 제품명과 카테고리를 직접 보완하면 `정보 보완 필요` 영역에서 벗어날 수 있다. 상세 동선은 [상품 확인과 편집](../inspect-and-edit-a-product.md)이 소유하고, 결정 근거는 [상품 정보 직접 보완의 완료 기준](../../history/product-planning/mvp/decisions/manual-completion.md)에 보존한다.

## 상태 문서의 범위

- 이 문서는 용어의 구분과 공통 완료 조건만 기록한다.
- 각 용어의 생성, 화면 노출, 전환, 재시도와 삭제 예외는 해당 사용자 흐름 문서가 소유한다. 저장·분석·재시도·처리 중 삭제는 [상품 저장](../save-a-product.md), 홈·일반 목록 노출·정보 보완·활성 상품 삭제는 [상품 확인과 편집](../inspect-and-edit-a-product.md), 분류·목적 검토와 중복 정리는 [구매 후보 정리](../organize-candidates.md)를 따른다.
- 아직 제안 상태인 원본 규칙이 있으므로 문서 상태를 승격하지 않는다.
