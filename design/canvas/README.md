# 디자인 캔버스 보드 생성기

디자인 캔버스(https://claude.ai/artifact/UFARxWnKuzLvjoY7eh7LTg, 비공개)의 `확정 디자인` 페이지 보드를 만드는 스크립트다. 캔버스 보드 원본은 쇼핑몰 상품 정보를 담고 있어 저장소에 넣지 않고, 필요할 때 캔버스에서 내려받아 `source/project/`에 둔다. `source/`, `build/`, `preview/`는 커밋하지 않는다.

## 만드는 보드

| 스크립트 | 보드 | 내용 |
| --- | --- | --- |
| `gen_purpose_detail.py` | `PurposeDetail.dc.html` | 확정된 목적 상세 부품. 후보 추가 카드·선택 창(PF-5), 길게 누르기 메뉴(LM-A), 구매한 상품 카드·비교 끝내기, `···` 메뉴를 담는다. `pkey`(closet·run·lamp)와 `start`(detail·picker·menu·finish) 속성을 받는다. |
| `gen_flow_view.py` | `Flow-Confirmed.dc.html` | 기능 흐름 뷰. `FLOW` 보드를 바탕으로 목적 상세를 `PurposeDetail` 부품으로 바꾸고 390×844, 흐림 6px로 맞춘다. |
| `gen_flow_map.py` | `Flow-Map.dc.html` | 흐름도. 확정 보드를 `dc-import`로 절반 크기로 불러와 화살표로 잇는다. |
| `gen_longpress.py` | `LM-A.dc.html` | 길게 누르기 메뉴 시안 보드. 다른 생성기가 이 파일의 조각을 불러 쓴다. |
| `gen_ui_review.py` | `UI-*.dc.html` | `UI 피드백 적용` 페이지. 확정 보드(기능 흐름 뷰·흐름도·목적 상세와 흐름도가 불러 쓰는 6개)를 복제해 UI 피드백을 반영한다. |
| `gen_colorsys.py` | `UI-*.dc.html` | 위 결과에 디자인 시스템 색(강하게 세트, 바탕 `#F7F7F3`)을 입힌다. 목적 상세 머리 면을 목적 색으로 깐다. |
| `gen_colorful.py` | `CP-*.dc.html` | `디자인 시스템 · 색` 페이지. 세트 견본, 세트별 목적 탭·목적 상세, 바탕 후보별 홈. |
| `gen_neutral.py` | `NEU-RAMP`, `NA-*`, `NB-*` | `탐색 · 무채색 단계` 페이지. 회색 단계 A안·B안(색 적용 전 `UI-*` 기준). |

`lib_pg.py`는 선택 창·필터·카테고리 시트 조각, `extract_pd_blocks.py`는 `PD-FINAL` 보드에서 시트 마크업을 꺼내는 스크립트다. `oklch.py`는 OKLCH·대비 계산, `palette_sets.py`는 색 세트 정의와 글자색 규칙, `canvas_src.py`는 원본 보드에서 헬멧·하단 탭·상품 데이터를 꺼내는 공용 모듈이다. 상품 이름과 사진 id는 스크립트에 두지 않고 원본 보드에서 읽는다.

## 사용법

1. 캔버스에서 다음 보드를 내려받아 `source/project/`에 둔다: `PD-FINAL`, `PF-5`, `FLOW`, `EP-D` (`.dc.html`).
2. 생성한다.

   ```sh
   python3 extract_pd_blocks.py
   python3 gen_purpose_detail.py build/out
   python3 gen_flow_view.py build/out
   python3 gen_flow_map.py build/out
   ```

   UI 피드백 적용·디자인 시스템 페이지는 확정 보드 원본(`Flow-Confirmed`, `PurposeDetail`, `Flow-Map`, `P-D`, `CT-D`, `PT-A8`, `A8-AR2`, `AD-F2`, `ARCH-ACT2`)을 `source/project/`에 두고 만든다.

   ```sh
   python3 gen_ui_review.py source/project build/ui-base
   python3 gen_colorsys.py build/ui-base build/ui
   python3 gen_colorful.py build/cp
   python3 gen_neutral.py build/ui-base build/neu
   ```

3. 결과를 캔버스의 같은 경로(`project/<이름>.dc.html`)로 게시한다. 게시 전에 캔버스의 해당 보드를 다시 읽어 사용자가 캔버스에서 직접 고친 내용을 덮어쓰지 않는지 확인한다.

원본 폴더를 바꾸려면 `CANVAS_SRC`, 시트 조각 파일을 바꾸려면 `PD_BLOCKS` 환경 변수를 쓴다.

## 로컬에서 보기

캔버스 페이지는 브라우저 자동화의 클릭에 반응하지 않을 때가 있어, 보드를 로컬에서 띄워 확인한다.

1. 캔버스의 `artifact-type/dc-runtime.js`를 내려받아 `preview/support.js`로 저장한다.
2. 보고 싶은 보드와 그 보드가 불러오는 보드(`dc-import`)를 `preview/`에 복사한다.
3. `preview/` 안에서 `python3 ../serve.py`를 실행하고 `http://127.0.0.1:8931/<보드>.dc.html`을 연다. `/_blob/…` 이미지는 회색 자리 표시로 대신한다.
4. 실제 상품 사진과 Pretendard 글꼴로 보려면 보드가 쓰는 `/_blob/<id>`를 Artifact read에 `path`로 id를 **하나씩** 넘겨 받고(`paths`로 여러 개를 넘기면 받아지지 않는다), `preview/_blob/<id>`에 확장자 없이 둔다. `serve.py`는 그 파일이 있으면 그대로 돌려준다.

- 길게 누르기처럼 클릭으로 열리지 않는 동작은 대상 요소에 JS로 `pointerdown`을 보내고 약 900ms 뒤 `pointerup`을 보낸다. 보드는 shadow DOM 안에 그려지므로 요소를 찾을 때 `shadowRoot`도 훑는다.
- Chrome 자동화가 응답하지 않으면 헤드리스 Chrome으로 캡처한다: `"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --window-size=390,844 --force-device-scale-factor=2 --virtual-time-budget=4000 --screenshot=out.png http://127.0.0.1:8931/<보드>.dc.html`. 캡처 뒤 프로세스가 끝나지 않을 수 있어 파일이 생기면 종료한다.
- 게시 전에 캔버스를 `read`(url만)로 한 번 읽어야 게시가 거절되지 않는다.
