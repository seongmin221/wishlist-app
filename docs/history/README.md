# 의사결정 이력

이 폴더는 “무엇을 선택했는지”보다 “왜 선택했고 무엇을 포기했는지”를 기록한다. 확정 전 문서는 `제안`, 채택 후에는 `확정`, 대체되면 `대체됨` 상태를 표시한다.

- [Client 결정](client/README.md)
- [Server 결정](server/README.md)
- [AI 결정](ai/README.md)
- [ADR-001: 프로젝트 문서와 구현 이력 기록](ADR-001-documentation-recording.md)
- [ADR-004: client, server, AI 자산을 분리한 monorepo](ADR-004-repository-structure.md)

## 기록 양식

```text
# 결정 제목
상태: 제안 | 확정 | 대체됨
날짜:

## 맥락
## 선택지
## 결정
## 이유와 trade-off
## 재검토 조건
```

영역을 가로지르는 작업 방식이나 repository 운영 결정은 이 폴더 루트에 기록한다. client, server, ai에 명확히 속하는 결정은 각각의 하위 폴더에 기록한다.
