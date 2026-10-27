# MVP 제품 결정

MVP 사용자 흐름별 제품 결정과 당시의 판단 근거를 모은다. 현재 제품 규칙의 단일 기준은 [MVP 제품 기획 이력](../INDEX.md)에 연결된 제품 기준 문서다.

## 상품 저장

- [로컬 대기 저장과 분석 재시도](local-pending-analysis.md) — 확정 · 2026-09-10
- [Product 캐시와 WishlistItem 스냅샷](product-cache-snapshot.md) — 확정 · 2026-09-05
- [중복 URL과 상품 후보 처리](duplicate-items.md) — 확정 · 2026-09-05
- [B5 비동기 분석과 운영 복구](b5-analysis-runtime-policy-2026-10-09.md) — 확정 · 2026-10-09 · 합산 3회·가격 범위 null·canonical eTLD+1·PENDING 5분·DNS Retryable·Retryable ACK

## 후보 정리

- [B2 카테고리 API 정책](b2-category-api-policy-2026-10-07.md) — 확정 · 2026-10-07
- [B3 목적 API 정책](b3-purpose-api-policy-2026-10-07.md) — 확정 · 2026-10-07

- [목적별 비교 그룹과 AI 목적 제안](purpose-groups.md) — 대체됨 · 2026-08-29
- [taxonomy 방향 정리](taxonomy-direction.md) — 대체됨 · 2026-09-02
- [사용자 전용 카테고리 변경과 삭제](custom-category-lifecycle.md) — 일부 대체 · 2026-09-05
- [사용자 전용 카테고리 삭제 뒤 재지정을 정보 보완으로 합침](custom-category-deletion-reassign.md) — 확정 · 2026-10-02
- [사용자 전용 카테고리 입력과 AI 안전성](custom-category-safety.md) — 확정 · 2026-09-05
- [AI 목적 자동 연결과 재판단](ai-purpose-linking.md) — 일부 대체 · 2026-09-05
- [연결된 상품이 있는 목적 삭제](purpose-deletion.md) — 확정 · 2026-09-05

## 상품 확인과 편집

- [B7 상품 사용자 변경](b7-item-mutation-policy-2026-10-14.md) — 확정 · 2026-10-14 · 상품명·브랜드 200자·편집 취소 뒤 category 유지·편집의 검토 상태·사진 해제 시 외부 이미지 복귀·PATCH 범위·재분석 60초 10건·owner 잠금 유지
- [B6 사용자 이미지 업로드](b6-media-upload-policy-2026-10-12.md) — 제품·운영 정책 확정 · JPEG 5MiB·긴 변 2048·signed PUT·15분 intent·24시간 미참조 정리·1시간 읽기 URL·외부 이미지 URL 보관·APP0·APP2 ICC·APP14 외 segment 거절·PENDING 10개
- [B4 상품 목록과 홈 조회](b4-read-api-policy-2026-10-07.md) — 제품 정책 확정 · 홈 목적 3개·빈 목적 포함·restart·목록 기준 category count·별도 할 일 합계 없음
- [상품 정보 직접 보완의 완료 기준](manual-completion.md) — 확정 · 2026-09-10
- [홈 조치 필요 영역의 우선순위](home-action-priority.md) — 2026-09-10 결정 · 카테고리 미지정 영역은 2026-10-02 정보 보완에 통합
- [상품 링크 웹뷰 동작](webview-behavior.md) — 확정 · 2026-09-05

## 구매 결정 마무리

- [아카이브와 삭제 안전장치](archive-delete-safeguards.md) — 확정 · 2026-09-05
- [B8 후보 이동과 삭제 정책](b8-candidate-move-deletion-policy-2026-10-24.md) — 확정 · 2026-10-24
- [B9 중복 후보 정책](b9-duplicate-resolution-policy-2026-10-24.md) — 확정 · 2026-10-24 · 판매처 간 식별 코드·쌍별 판단·검토 밖 후보 저장
