# 재디자인 캔버스 보드 생성기

[위시리스트 디자인 캔버스](https://claude.ai/artifact/DDuoeFQ7k7i9yBqY2H9qDK)의 `전체 화면 · 라이트`, `전체 화면 · 다크` 보드를 만드는 스크립트다. 라이트·다크는 같은 구조이고 테마 값(`gen_full_home.py`의 `THEMES`)만 다르다.

| 스크립트 | 보드 |
| --- | --- |
| `gen_full_home.py` | 홈(`FHome*`), 분류·목적 확인과 중복 후보(`FHomeReviewFlow*`, `FDuplicate*`), 정보 보완(`FHomeFillFlow*`). 공용 테마 값·서체·위험 색도 여기 있다. |
| `gen_category.py` | 카테고리 묶음(`FCategory*`) |
| `gen_purpose.py` | 목적 묶음(`FPurpose*`) |
| `gen_product.py` | 상품 묶음(`FProduct*`). 카테고리 선택 시트의 분류 데이터는 `taxonomy.json` |
| `gen_archive.py` | 아카이브(끝난 비교) 묶음(`FArchive*`) |
| `gen_account.py` | 로그인 안내, 홈 로그인 전, 설정, 공유 수신, 원본 링크 웹뷰(`FLogin*`, `FHomeLoggedOut*`, `FSettings*`, `FShareSaved*`, `FWebView*`) |
| `gen_infocolor.py` | `검토 · 정보 아이콘 색` 페이지의 후보 보드(`PickInfo*`) |
| `gen_motion.py` | `검토 · 화면 전환 모션` 페이지의 후보 보드(`Motion*`). 상태별 화면 캡처(`motion/*.png`)를 겹쳐 CSS 애니메이션으로 반복 재생한다 |
| `capture_motion_states.py` | `gen_motion.py`가 쓰는 상태별 화면을 핸드오프 보드에서 2배 해상도로 찍는다 |
| `capture_motion_strips.py` | 핸드오프 `interactions/reference/boards/`의 모션 보드에서 주요 시점 프레임을 찍어 `reference/strips/*.png`를 만든다 |
| `gen_vis.py` | `시각 방향 · 대표 화면`의 목적 보드(`VisPurpose*`). 전체 화면 목적 보드에서 연결만 바꾼다 |

## 만들기

```sh
for g in gen_full_home gen_category gen_purpose gen_product gen_archive gen_account gen_vis; do python3 $g.py <out>; done
```

`<out>/project/`에 보드가 생긴다. `gen_full_home.py`는 이미 결정이 끝난 비교 보드(`Pick*`)도 함께 만들므로 게시 전에 지운다.

## 게시

1. 캔버스 `project/canvas.json`을 Artifact read로 새로 읽는다. 사용자가 캔버스에서 직접 고친 보드가 있으면 생성기에 먼저 반영한다. 생성기로 다시 만들면 캔버스의 직접 수정은 덮어쓰인다.
2. 보드를 추가할 때만 `canvas.json`에 `boards`·`order`를 더해 함께 보낸다. 한 줄은 `y` 1264 간격, 보드는 `x` 470 간격이다(0 홈, 1264 카테고리, 2528 목적, 3792 상품, 5056 아카이브, 6320 로그인·설정·공유·웹뷰).
3. Artifact publish에 `root`를 `<out>`으로 두고 바뀐 보드만 보낸다.

## 로컬에서 보기

1. 캔버스의 `artifact-type/dc-runtime.js`를 받아 `<out>/project/support.js`로 둔다.
2. `python3 -m http.server <포트> --directory <out>/project`로 띄운다.
3. 헤드리스 캡처: `chrome-headless-shell --window-size=390,844 --virtual-time-budget=5000 --hide-scrollbars --screenshot=out.png http://127.0.0.1:<포트>/<보드>.dc.html`. Chrome이 없으면 Playwright가 받아 둔 `~/Library/Caches/ms-playwright/chromium_headless_shell-*/…/chrome-headless-shell`을 쓴다.
