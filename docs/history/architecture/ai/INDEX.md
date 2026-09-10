# AI 의사결정

## 초기 결정

- **확정**: MVP에서 자체 ML 모델 학습·호스팅과 GPU 운영은 하지 않는다.
- **확정**: 외부 LLM API를 사용한다.
- **확정**: deterministic parsing을 AI보다 먼저 사용한다.
- **확정**: AI는 predefined taxonomy의 category ID만 선택한다.
- **확정**: 사용자 category 수정값과 model/taxonomy version을 보존한다.
- **확정**: MVP에서는 embedding, vector DB, RAG를 도입하지 않는다.

추가 결정은 `ADR-번호-제목.md` 형식으로 이 폴더에 기록한다.
