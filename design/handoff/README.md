# 디자인 핸드오프

[위시리스트 디자인 캔버스](https://claude.ai/artifact/DDuoeFQ7k7i9yBqY2H9qDK)는 로그인이 필요한 비공개 아티팩트라서, 레포 밖 도구(Codex 등)가 읽을 수 있도록 보드를 옮겨 둔 사본이다.

| 폴더 | 내용 | 쓰임 |
| --- | --- | --- |
| [screens/](screens/README.md) | 완성 화면 52개 × 라이트·다크 (보드 104개) | **구현 기준.** 색·서체·간격·상태까지 확정된 최종 화면 |
| [interactions/](interactions/README.md) | 모션 명세(시간·곡선·제스처·플랫폼 메모), 모션 토큰, 화면별 상태와 동작, 모션 프레임 스트립·보드 | 클라이언트에서 움직임과 상태를 구현할 때 |
| [wireframes/](wireframes/README.md) | 흑백 와이어프레임 43개와 흐름도 | 화면 구조와 이동 흐름 참고. 흐름도는 최신 화면 이름을 따른다 |

읽는 순서

1. [제품 문서 INDEX](../../docs/product/INDEX.md): 기능 규칙
2. [디자인 결정](../../docs/design/decisions.md): 시각·구조 결정과 근거, 색·서체 값
3. [흐름도](wireframes/shots/FlowMap.png): 화면 사이 이동
4. [완성 화면](screens/README.md): 화면별 라이트·다크 보드
5. [인터랙션 핸드오프](interactions/README.md): 화면 상태와 동작, 모션 값

세 자료가 서로 다르면 디자인 결정 → 완성 화면 → 와이어프레임 순으로 따른다.
