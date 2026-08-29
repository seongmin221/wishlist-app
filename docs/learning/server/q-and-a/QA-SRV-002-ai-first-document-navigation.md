# QA-SRV-002: AI 탐색을 위한 문서 INDEX 전략

> 상태: **학습 Q&A** · 날짜: 2026-08-28

## 질문

사람보다 AI가 문서를 탐색하는 것을 염두에 둘 때, 모든 문서 폴더에 하위 파일을 나열하는 `INDEX.md`를 두는 것이 좋은가?

## 짧은 답변

모든 폴더에 기계적으로 INDEX를 두는 것은 권장하지 않는다. AI는 filename 검색과 content 검색을 할 수 있으므로, 중복된 전체 파일 목록은 context와 유지보수 비용만 늘릴 수 있다. 여러 하위 주제가 있거나 진입점 역할을 하는 문서 카테고리에만 짧고 정규화된 INDEX를 둔다.

## 권장 규칙

- `docs/`, `architecture/`, `learning/`, `history/` 같은 큰 카테고리에는 INDEX를 둔다.
- `learning/server/ktor/`처럼 여러 문서가 있고 학습 순서가 있는 폴더에는 INDEX를 둔다.
- 문서 하나만 있는 leaf folder에는 INDEX를 만들지 않는다.
- 문서 이름에 안정적인 ID와 주제를 포함한다. 예: `LEARN-KTOR-002-json-validation-error-handling.md`.
- 새 문서에는 가능한 경우 ID, type, area, topic, summary, related를 YAML front matter로 둔다.
- INDEX에는 전체 내용을 복사하지 않고 ID, 요약, 링크, 하위 카테고리만 둔다.

## 왜 중요한가

AI가 정확한 문서를 빠르게 찾으려면 깊은 folder tree보다 안정적인 이름, 짧은 요약 metadata, 관련 문서 링크가 더 효과적이다. INDEX는 탐색 시작점을 제공하되, 원문보다 더 많은 정보를 중복하면 안 된다.
