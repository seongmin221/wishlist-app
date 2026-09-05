# 제품 기획 재개 체크포인트 (2026-09-04)

> 상태: **대체됨** · 날짜: 2026-09-04 · 영역: **제품 공통**

이 체크포인트 이후 제품 정책과 `Product`·`WishlistItem` 경계가 구체화됐다. 현재 재개 지점은 [2026-09-05 기획 체크포인트](PRODUCT-PLANNING-CHECKPOINT-2026-09-05.md)를 따른다.

2026-08-29 체크포인트 이후 확정한 제품 기획을 기록한다. 제품 규칙의 기준 문서는 [제품 기능 및 스펙](../product-spec.md)이며, taxonomy의 전체 목록은 [제품 taxonomy](../product-taxonomy-draft.md)에서 확인한다.

## 이번까지 확정한 방향

- 공용 상품 taxonomy는 상위 카테고리 11개와 세부 유형 87개로 첫 출시를 시작한다. 이후에는 미분류, 반복 사용자 전용 카테고리, 반복 수동 수정 로그를 근거로 확장한다.
- 카테고리는 상품이 무엇인지, 목적은 사용자가 왜 비교하는지 나타내는 별도 개념이다. 활성 항목은 카테고리 하나와 목적 0~1개를 가진다.
- AI는 공용 taxonomy를 새로 만들지 않고, 새 항목을 사용자가 이미 만든 목적에만 연결할 수 있다. 확신이 낮으면 목적 미지정으로 남긴다. AI가 새 목적을 만들거나 추천 문구를 띄우지 않는다.
- 사용자는 목적 탭의 `목적 추가`, 새 항목의 `분류·목적 확인`, 상품 편집 화면에서 목적을 만들거나 연결·변경·해제할 수 있다. 빈 목적은 자동 삭제하지 않는다.
- `분류·목적 확인`의 오른쪽 스와이프는 확정, 왼쪽 스와이프는 보류다. 보류 항목은 이 영역에 다시 자동 노출하지 않고 일반 목록에서 미확정임을 표시한다.
- 사용자는 기존 상위 카테고리 아래에 개인용 세부 카테고리를 추가할 수 있다. 이름만 필수이고 짧은 설명·포함 예시는 선택이다. 서버는 사용자 수정 절차 없이 AI 입력 안전성만 내부적으로 통제한다.
- 구매 결정을 내리면 목적과 포함 후보를 함께 아카이브한다. 개별 후보를 복원해 다른 목적에서 다시 고려할 수 있으며, 기존 아카이브 기록은 보존한다.

## 문서·구현 상태

- 제품·taxonomy·AI 구조 문서를 갱신했다. 이 체크포인트 시점에도 앱과 서버 기능 구현은 시작하지 않았다.
- 공용 taxonomy의 구체적인 목록은 [제품 taxonomy](../product-taxonomy-draft.md)에 있다. `헤드폰`에는 게이밍 헤드셋을 포함하고, `의자`는 사무용·게이밍·식탁 의자를 함께 둔다. 스킨케어와 메이크업은 분리하며, 키보드는 하나의 세부 유형이다.

## 다음 재개 지점

비어 있지 않은 목적의 삭제 규칙은 2026-09-05에 [별도 결정](PRODUCT-PLANNING-DECISION-2026-09-05-PURPOSE-DELETION.md)으로 확정했다.

사용자 전용 카테고리의 이름 변경·삭제 규칙은 2026-09-05에 [별도 결정](PRODUCT-PLANNING-DECISION-2026-09-05-CUSTOM-CATEGORY-LIFECYCLE.md)으로 확정했다.

AI 목적 자동 연결의 최소 근거와 재판단 조건은 2026-09-05에 [별도 결정](PRODUCT-PLANNING-DECISION-2026-09-05-AI-PURPOSE-LINKING.md)으로 확정했다.

중복 URL과 상품 후보 처리 규칙은 2026-09-05에 [별도 결정](PRODUCT-PLANNING-DECISION-2026-09-05-DUPLICATE-ITEMS.md)으로 확정했다.

아카이브와 삭제 안전장치는 2026-09-05에 [별도 결정](PRODUCT-PLANNING-DECISION-2026-09-05-ARCHIVE-DELETE-SAFEGUARDS.md)으로 확정했다.

웹뷰 동작은 2026-09-05에 [별도 결정](PRODUCT-PLANNING-DECISION-2026-09-05-WEBVIEW-BEHAVIOR.md)으로 확정했다.

사용자 전용 카테고리의 안전성 제한과 AI 후보 제외 기준은 2026-09-05에 [별도 결정](PRODUCT-PLANNING-DECISION-2026-09-05-CUSTOM-CATEGORY-SAFETY.md)으로 확정했다.

`Product`를 공용 추출 캐시로만 사용하고 `WishlistItem`을 독립 snapshot으로 두는 경계는 2026-09-05에 [별도 결정](PRODUCT-PLANNING-DECISION-2026-09-05-PRODUCT-CACHE-SNAPSHOT.md)으로 확정했다.

1. **Product 캐시 상태별 재사용 기준 결정 (최우선)**
2. 전체 데이터 모델과 API 계약 설계
3. PostgreSQL, Auth, Queue, Hosting 공급자와 처리 완료 전달 방식 결정
4. Playwright 적용 범위 결정

## 관련 문서

- [제품 기능 및 스펙](../product-spec.md)
- [제품 taxonomy](../product-taxonomy-draft.md)
- [taxonomy 방향 정리](PRODUCT-TAXONOMY-DIRECTION-2026-09-02.md)
- [목적 삭제 결정](PRODUCT-PLANNING-DECISION-2026-09-05-PURPOSE-DELETION.md)
- [사용자 전용 카테고리 변경·삭제 결정](PRODUCT-PLANNING-DECISION-2026-09-05-CUSTOM-CATEGORY-LIFECYCLE.md)
- [AI 목적 자동 연결 결정](PRODUCT-PLANNING-DECISION-2026-09-05-AI-PURPOSE-LINKING.md)
- [중복 URL과 상품 후보 처리 결정](PRODUCT-PLANNING-DECISION-2026-09-05-DUPLICATE-ITEMS.md)
- [아카이브와 삭제 안전장치 결정](PRODUCT-PLANNING-DECISION-2026-09-05-ARCHIVE-DELETE-SAFEGUARDS.md)
- [상품 링크 웹뷰 동작 결정](PRODUCT-PLANNING-DECISION-2026-09-05-WEBVIEW-BEHAVIOR.md)
- [사용자 전용 카테고리 입력·AI 안전성 결정](PRODUCT-PLANNING-DECISION-2026-09-05-CUSTOM-CATEGORY-SAFETY.md)
- [Product 캐시와 WishlistItem 스냅샷 결정](PRODUCT-PLANNING-DECISION-2026-09-05-PRODUCT-CACHE-SNAPSHOT.md)
- [AI 구조](../architecture/ai/overview.md)
