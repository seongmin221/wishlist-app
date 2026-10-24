# 설계 spec

- [저비용 OpenAI 분류](2026-09-19-openai-low-cost-classification-design.md) — 분류 모델·출력·예산
- [MVP 서버 운영과 평가](2026-09-22-mvp-server-operation-and-evaluation-design.md) — 서버 운영·AI 평가·browser fallback
- [클라이언트 구현 로드맵](2026-10-05-client-implementation-roadmap-design.md) — 서버 계약과 KMP 구현 단계
- [B3 목적 관리](2026-10-07-b3-purpose-management-design.md) — 목적 조회·생성·편집·AI 후보
- [B4 상품 목록·홈 조회](2026-10-07-b4-read-api-design.md) — scope·group·anchor·cursor·snapshot·인덱스
- [C4 상품 상세·분석 중·웹뷰](2026-10-09-client-c4-product-detail-design.md) — 상세 Presenter·로컬 대기 삭제·목적 표시 캐시·웹뷰 탐색
- [B5 비동기 분석·운영 복구](2026-10-09-b5-analysis-runtime-recovery-design.md) — 구현 완료 · 합산 재시도 예산·metadata·canonical·egress proxy·maintenance·PENDING 복구
- [B6 사용자 이미지 업로드](2026-10-12-b6-media-upload-design.md) — 구현 완료 · JPEG 5MiB·signed PUT 직접 업로드·15분 intent·completion 검증·표시 URL·24시간 미참조 정리
- [B7 상품 사용자 변경](2026-10-14-b7-item-mutations-design.md) — 구현 완료 · 편집·직접 보완·검토·삭제·재분석·deletionImpact·잠금/오류 순서
- [B8 후보 이동과 영향 확인 삭제 설계](2026-10-24-b8-candidate-move-deletions-design.md) — B8a/b/c 계약·원자성·삭제 재검증
