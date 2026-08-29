# Wishlist 앱 문서

이 디렉터리는 제품 기획, 아키텍처, 기술 학습, 의사결정 이력을 코드와 같은 Git 이력으로 관리한다. 모든 문서는 Markdown으로 작성하며, 나중에 GitHub Pages의 정적 문서 사이트 입력으로 사용한다.

## 문서 상태

- **확정**: 현재 구현과 후속 설계의 기준이다.
- **제안**: 방향은 합의됐지만 기술 선택 또는 세부 설계가 남아 있다.
- **검토 예정**: 의도적으로 아직 선택하지 않았다.

## 시작점

- [문서 INDEX](INDEX.md)
- [제품 기능·스펙](product-spec.md)
- [전체 서비스 구조](architecture/README.md)
- [클라이언트 구조](architecture/client/README.md)
- [서버 구조](architecture/server/overview.md)
- [AI 구조](architecture/ai/overview.md)
- [기술 학습 노트](learning/README.md)
- [의사결정 이력](history/README.md)

구현 사유와 trade-off를 남기는 프로젝트 공통 기록 방식은 [ADR-001](history/ADR-001-documentation-recording.md)을 따른다.

## 작성 규칙

1. 기능 또는 동작이 바뀌면 먼저 `product-spec.md`를 갱신한다.
2. 구조적 선택은 근거와 대안을 포함해 `history/`에 기록한다.
3. 새 기술을 채택하면 해당 `learning/` 문서에 이 프로젝트의 사용 맥락과 함께 설명한다.
4. 비밀값, 사용자 URL, 접근 토큰, 민감한 운영 정보는 문서에 커밋하지 않는다.
