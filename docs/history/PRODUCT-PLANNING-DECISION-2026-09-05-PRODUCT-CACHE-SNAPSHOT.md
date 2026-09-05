# 기술 결정: Product 캐시와 WishlistItem 스냅샷

> 상태: **확정** · 날짜: 2026-09-05 · 영역: **제품·서버**

## 결정

- `Product`는 canonical URL과 서버 추출 정보를 재사용하기 위한 공용 캐시로 사용한다.
- 새 저장 요청에 대응하는 `Product` 캐시가 있으면 그 시점의 상품 정보를 새 `WishlistItem`에 복사해 빠르게 항목을 생성할 수 있다.
- `WishlistItem`은 표시용 상품 정보를 자체 보관하는 사용자별 독립 snapshot이다.
- 생성 시점의 복사 이후 `Product`와 `WishlistItem` 사이에는 어느 방향으로도 동기화하지 않는다.
- `Product` 정보가 이후 보완되거나 변경돼도 기존 `WishlistItem`을 갱신하지 않는다.
- 사용자가 `WishlistItem`의 제품명·대표 이미지 등을 수정해도 `Product` 캐시나 다른 사용자의 항목에 반영하지 않는다.
- `WishlistItem`은 화면 조회 시 `Product` metadata를 실시간으로 읽어 표시하지 않는다.
- 추출 작업은 새 저장 요청의 대상 상품만 처리한다. 관련 상품이나 기존 항목을 연쇄적으로 추출·갱신하지 않는다.

## 데이터 소유 경계

### Product

- canonical URL
- 서버가 추출한 재사용 가능한 상품 metadata
- 추출 시각과 출처 등 캐시 판단 정보

### WishlistItem

- 사용자와 원본 공유 URL
- 생성 시 복사되거나 대상 추출로 얻은 표시용 상품 정보
- 사용자 카테고리와 목적
- 처리·검토 상태
- 사용자가 직접 수정한 값

`WishlistItem`이 어떤 `Product` 캐시에서 만들어졌는지 출처 식별자를 보관할 수는 있다. 이 연결은 중복 탐지나 추적을 위한 것이며 동기화를 의미하지 않는다.

## 판단 근거

공용 `Product`를 사용하는 목적은 이미 추출한 상품 정보를 재사용해 새 항목을 빠르게 만드는 것이다. 이를 `WishlistItem`의 실시간 원본으로 사용하면 다른 저장 요청이나 캐시 보완 때문에 사용자가 보고 있던 상품 정보가 예고 없이 달라질 수 있다.

항목을 생성 시점의 독립 snapshot으로 만들면 사용자 수정의 범위가 분명해지고, 캐시 갱신·다른 사용자·기존 항목 사이의 동기화 규칙이 필요하지 않다. 가격 등 정보가 오래될 수 있으므로 원래 값과 마지막 확인 시점을 함께 표시한다는 기존 제품 원칙을 유지한다.

## 제품·구현 영향

- `WishlistItem`은 화면 표시에 필요한 상품 metadata를 직접 저장해야 한다.
- `Product` 변경 이벤트로 기존 항목을 갱신하는 worker나 sync 로직을 만들지 않는다.
- 사용자 수정 API는 `WishlistItem`만 변경해야 한다.
- 신규 저장 pipeline만 `Product` 캐시를 조회하고 필요할 때 대상 상품을 추출한다.
- 캐시 상태별 재사용 기준은 데이터 모델·API 상세 설계에서 후속 결정한다.

## 관련 문서

- [제품 기능 및 스펙](../product-spec.md)
- [Server 구조](../architecture/server/overview.md)
- [상품 정보 추출 pipeline](../architecture/server/extraction-pipeline.md)
