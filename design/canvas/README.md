# 디자인 캔버스 보드 생성기

디자인 캔버스(https://claude.ai/artifact/UFARxWnKuzLvjoY7eh7LTg, 비공개)의 `확정 디자인` 페이지 보드를 만드는 스크립트다. 캔버스 보드 원본은 쇼핑몰 상품 정보를 담고 있어 저장소에 넣지 않고, 필요할 때 캔버스에서 내려받아 `source/project/`에 둔다. `source/`, `build/`, `preview/`는 커밋하지 않는다.

## 만드는 보드

| 스크립트 | 보드 | 내용 |
| --- | --- | --- |
| `gen_purpose_detail.py` | `PurposeDetail.dc.html` | 확정된 목적 상세 부품. 후보 추가 카드·선택 창(PF-5), 길게 누르기 메뉴(LM-A), 구매한 상품 카드·비교 끝내기, `···` 메뉴를 담는다. `pkey`(closet·run·lamp)와 `start`(detail·picker·menu·finish) 속성을 받는다. |
| `gen_flow_view.py` | `Flow-Confirmed.dc.html` | 기능 흐름 뷰. `FLOW` 보드를 바탕으로 목적 상세를 `PurposeDetail` 부품으로 바꾸고 390×844, 흐림 6px로 맞춘다. |
| `gen_flow_map.py` | `Flow-Map.dc.html` | 흐름도. 확정 보드를 `dc-import`로 절반 크기로 불러와 화살표로 잇는다. |
| `gen_longpress.py` | `LM-A.dc.html` | 길게 누르기 메뉴 시안 보드. 다른 생성기가 이 파일의 조각을 불러 쓴다. |

`lib_pg.py`는 선택 창·필터·카테고리 시트 조각, `extract_pd_blocks.py`는 `PD-FINAL` 보드에서 시트 마크업을 꺼내는 스크립트다.

## 사용법

1. 캔버스에서 다음 보드를 내려받아 `source/project/`에 둔다: `PD-FINAL`, `PF-5`, `FLOW`, `EP-D` (`.dc.html`).
2. 생성한다.

   ```sh
   python3 extract_pd_blocks.py
   python3 gen_purpose_detail.py build/out
   python3 gen_flow_view.py build/out
   python3 gen_flow_map.py build/out
   ```

3. 결과를 캔버스의 같은 경로(`project/<이름>.dc.html`)로 게시한다. 게시 전에 캔버스의 해당 보드를 다시 읽어 사용자가 캔버스에서 직접 고친 내용을 덮어쓰지 않는지 확인한다.

원본 폴더를 바꾸려면 `CANVAS_SRC`, 시트 조각 파일을 바꾸려면 `PD_BLOCKS` 환경 변수를 쓴다.

## 로컬에서 보기

캔버스 페이지는 브라우저 자동화의 클릭에 반응하지 않을 때가 있어, 보드를 로컬에서 띄워 확인한다.

1. 캔버스의 `artifact-type/dc-runtime.js`를 내려받아 `preview/support.js`로 저장한다.
2. 보고 싶은 보드와 그 보드가 불러오는 보드(`dc-import`)를 `preview/`에 복사한다.
3. `preview/` 안에서 `python3 ../serve.py`를 실행하고 `http://127.0.0.1:8931/<보드>.dc.html`을 연다. `/_blob/…` 이미지는 회색 자리 표시로 대신한다.
