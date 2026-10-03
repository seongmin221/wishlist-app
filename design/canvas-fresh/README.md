# 재디자인 캔버스 보드 생성기

[위시리스트 디자인 캔버스](https://claude.ai/artifact/DDuoeFQ7k7i9yBqY2H9qDK)의 `전체 화면 · 라이트`, `전체 화면 · 다크` 보드를 만드는 스크립트다. 라이트·다크는 같은 구조이고 테마 값(`gen_full_home.py`의 `THEMES`)만 다르다.

| 스크립트 | 보드 |
| --- | --- |
| `gen_full_home.py` | 홈(`FHome*`), 분류·목적 확인과 중복 후보(`FHomeReviewFlow*`, `FDuplicate*`), 정보 보완(`FHomeFillFlow*`). 공용 테마 값·서체·위험 색도 여기 있다. |
| `gen_category.py` | 카테고리 묶음(`FCategory*`) |
| `gen_purpose.py` | 목적 묶음(`FPurpose*`) |

## 만들기

```sh
python3 gen_full_home.py <out> && python3 gen_category.py <out> && python3 gen_purpose.py <out>
```

`<out>/project/`에 보드가 생긴다. `gen_full_home.py`는 이미 결정이 끝난 비교 보드(`Pick*`)도 함께 만들므로 게시 전에 지운다.

## 게시

1. 캔버스 `project/canvas.json`을 Artifact read로 새로 읽는다. 사용자가 캔버스에서 직접 고친 보드가 있으면 생성기에 먼저 반영한다. 생성기로 다시 만들면 캔버스의 직접 수정은 덮어쓰인다.
2. 보드를 추가할 때만 `canvas.json`에 `boards`·`order`를 더해 함께 보낸다. 한 줄은 `y` 1264 간격, 보드는 `x` 470 간격이다(0 홈, 1264 카테고리, 2528 목적).
3. Artifact publish에 `root`를 `<out>`으로 두고 바뀐 보드만 보낸다.

## 로컬에서 보기

1. 캔버스의 `artifact-type/dc-runtime.js`를 받아 `<out>/project/support.js`로 둔다.
2. `python3 -m http.server <포트> --directory <out>/project`로 띄운다.
3. 헤드리스 캡처: `chrome-headless-shell --window-size=390,844 --virtual-time-budget=5000 --hide-scrollbars --screenshot=out.png http://127.0.0.1:<포트>/<보드>.dc.html`. Chrome이 없으면 Playwright가 받아 둔 `~/Library/Caches/ms-playwright/chromium_headless_shell-*/…/chrome-headless-shell`을 쓴다.
