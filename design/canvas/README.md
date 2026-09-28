# 디자인 캔버스 보드 생성기

디자인 캔버스(https://claude.ai/artifact/UFARxWnKuzLvjoY7eh7LTg, 비공개)의 보드를 만들어 온 스크립트다. 캔버스 보드 원본은 쇼핑몰 상품 정보를 담고 있어 저장소에 넣지 않고, 필요할 때 캔버스에서 내려받아 `source/project/`에 둔다. `source/`, `build/`, `preview/`는 커밋하지 않는다.

## 지금 기준

2026-09-28부터 캔버스의 확정 보드 자체가 기준이다. 확정 보드를 고칠 때는 캔버스에서 해당 보드를 새로 내려받아 그 파일을 고치고 같은 경로로 게시한다. 아래 스크립트는 확정 보드를 만들어 온 기록이고, 한 번 더 돌리면 이미 반영된 변경을 두 번 적용하므로 지금의 확정 보드에는 다시 돌리지 않는다.

- `확정 디자인` 페이지: `Flow-Confirmed`(기능 흐름 뷰), `Flow-Map`(흐름도), `PurposeDetail`(목적 상세 부품)
- `확정 화면` 페이지: 흐름도가 불러 쓰는 `P-D`, `EH-A`~`EH-C`, `CT-D`, `PT-A8`, `A8-AR2`, `AD-F2`, `ARCH-ACT2`와 예전 통합 흐름 `FLOW`, 시트 원본 `PD-FINAL`
- `UI 피드백 적용` 페이지: 확정 전 비교에 쓴 `UI-*` 복제본
- `디자인 시스템` 페이지: 값과 공통 요소(`DS-*`)
- `디자인 시스템 · 색` 페이지: 색 세트 비교(`CP-*`)

## 확정 보드를 만든 과정 (2026-09-28)

UI 피드백 이전의 확정 보드(캔버스 버전 294의 `Flow-Confirmed`, `PurposeDetail`, `Flow-Map`, `P-D`, `EH-A`~`EH-C`, `CT-D`, `PT-A8`, `A8-AR2`, `AD-F2`, `ARCH-ACT2`)를 `source/project/`에 두고 만든다.

```sh
python3 gen_ui_review.py source/project build/ui-base   # UI 피드백 → UI-*
python3 gen_colorsys.py build/ui-base build/ui          # 확정 톤 +, 무채색 A안, 목적 상세 머리는 색 없음
python3 gen_promote.py build/ui source/project build/confirmed   # UI-* → 확정 보드 이름·제목
```

| 스크립트 | 내용 |
| --- | --- |
| `gen_ui_review.py` | 확정 보드를 `UI-*`로 복제해 UI 피드백(라벨 글꼴, keep-all, 작은 사진 바탕, 이름 두 줄, 개수 표기, 문구, 목록 위쪽 흐림 등)을 반영한다. |
| `gen_colorsys.py` | `UI-*`에 디자인 시스템 색(확정 톤 +, 바탕 `#F7F7F3`)과 무채색 단계 A안을 입힌다. |
| `gen_promote.py` | `UI-*`를 확정 보드 이름으로 바꾸고 `dc-import` 이름과 제목을 확정 보드 것으로 되돌린다. |
| `gen_design_system.py` | `디자인 시스템` 페이지의 `DS-*` 보드. 확정 보드(`build/confirmed` 또는 캔버스에서 내려받은 확정 보드)에서 헬멧·하단 탭·사진을 읽는다. `python3 gen_design_system.py <확정 보드 폴더> build/ds` |
| `gen_colorful.py` | `디자인 시스템 · 색` 페이지의 `CP-*` 보드. 원본은 `PT-A8`, `PurposeDetail`, `P-D`(UI 피드백 이전 것). |
| `gen_neutral.py` | 지운 `탐색 · 무채색 단계` 페이지의 A안·B안 보드 기록. |
| `palette_sets.py`, `oklch.py` | 색 세트 정의, 무채색 단계(`NEUTRAL`), OKLCH·대비 계산 |
| `canvas_src.py` | 원본 보드에서 헬멧·하단 탭·상품 데이터를 꺼내는 공용 모듈 |

그 이전의 확정 보드는 `gen_purpose_detail.py`(목적 상세 부품), `gen_flow_view.py`(기능 흐름 뷰), `gen_flow_map.py`(흐름도), `gen_longpress.py`(길게 누르기 시안)로 만들었다. 이들이 쓰던 `PF-5`, `EP-D`, `LM-A`는 탐색 페이지와 함께 지웠고, 캔버스 버전 294까지의 기록에 남아 있다. `lib_pg.py`, `extract_pd_blocks.py`는 이 과정의 조각 모듈이다. 상품 이름과 사진 id는 스크립트에 두지 않고 원본 보드에서 읽는다.

게시 전에 캔버스의 해당 보드를 다시 읽어 사용자가 캔버스에서 직접 고친 내용을 덮어쓰지 않는지 확인한다. 원본 폴더를 바꾸려면 `CANVAS_SRC`, 시트 조각 파일을 바꾸려면 `PD_BLOCKS` 환경 변수를 쓴다.

## 로컬에서 보기

캔버스 페이지는 브라우저 자동화의 클릭에 반응하지 않을 때가 있어, 보드를 로컬에서 띄워 확인한다.

1. 캔버스의 `artifact-type/dc-runtime.js`를 내려받아 `preview/support.js`로 저장한다.
2. 보고 싶은 보드와 그 보드가 불러오는 보드(`dc-import`)를 `preview/`에 복사한다.
3. `preview/` 안에서 `python3 ../serve.py`를 실행하고 `http://127.0.0.1:8931/<보드>.dc.html`을 연다. `/_blob/…` 이미지는 회색 자리 표시로 대신한다.
4. 실제 상품 사진과 Pretendard 글꼴로 보려면 보드가 쓰는 `/_blob/<id>`를 Artifact read에 `path`로 id를 **하나씩** 넘겨 받고(`paths`로 여러 개를 넘기면 받아지지 않는다), `preview/_blob/<id>`에 확장자 없이 둔다. `serve.py`는 그 파일이 있으면 그대로 돌려준다.

- 길게 누르기처럼 클릭으로 열리지 않는 동작은 대상 요소에 JS로 `pointerdown`을 보내고 약 900ms 뒤 `pointerup`을 보낸다. 보드는 shadow DOM 안에 그려지므로 요소를 찾을 때 `shadowRoot`도 훑는다.
- Chrome 자동화가 응답하지 않으면 헤드리스 Chrome으로 캡처한다: `"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --window-size=390,844 --force-device-scale-factor=2 --virtual-time-budget=4000 --screenshot=out.png http://127.0.0.1:8931/<보드>.dc.html`. 캡처 뒤 프로세스가 끝나지 않을 수 있어 파일이 생기면 종료한다. Chrome이 없는 기기에서는 Playwright가 받아 둔 `~/Library/Caches/ms-playwright/chromium_headless_shell-*/chrome-headless-shell-mac-arm64/chrome-headless-shell`에 같은 옵션(`--headless=new` 없이)을 쓴다.
- 게시 전에 캔버스를 `read`(url만)로 한 번 읽어야 게시가 거절되지 않는다.
