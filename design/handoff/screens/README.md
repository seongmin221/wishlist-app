# 완성 화면 핸드오프

[위시리스트 디자인 캔버스](https://claude.ai/artifact/DDuoeFQ7k7i9yBqY2H9qDK)의 `전체 화면 · 라이트`, `전체 화면 · 다크` 페이지(보드 104개)를 레포 밖 도구(Codex 등)가 읽을 수 있도록 옮긴 사본이다. 와이어프레임에 확정된 시각 규칙(색, 서체, 바탕, 모서리, 상태 색, 다크 모드)을 모두 입힌 **최종 화면**이며, 구현의 기준이다.

## 이 사본을 읽는 법

- **이 폴더가 기준이다.** 화면 구조·문구·이동은 [와이어프레임](../wireframes/README.md)과 같고, 시각 디자인은 이 폴더가 정한다. 둘이 다르면 이 폴더와 [디자인 결정](../../../docs/design/decisions.md)을 따른다.
- **화면마다 라이트(`…L.dc.html`)와 다크(`…D.dc.html`) 두 보드가 있다.** 구조는 같고 색 값만 다르다.
- **HTML이 기준이다.** 글자, 크기(px), 간격, 모서리, 색 값, 화면 이동(`href`)이 그대로 있다. PNG는 390×844 첫 화면·초기 상태만 찍었다. 시트·확인창·펼침 같은 다른 상태는 대부분 별도 보드로 그려 두었고, 나머지는 HTML의 상태 전이(`setState`)에서 확인한다. 동작과 애니메이션 값은 [인터랙션 핸드오프](../interactions/README.md)에 정리했다.
- **색·서체 값의 한 곳은 결정 문서다.** [디자인 결정](../../../docs/design/decisions.md)의 `시각 방향`·`디자인 시스템 규칙` 표에 라이트·다크 값과 근거가 있다. 보드를 만든 생성기([`design/canvas-fresh/gen_full_home.py`](../../canvas-fresh/gen_full_home.py)의 `THEMES`, `STATUS`, `DANGER`)에도 같은 값이 있다.
- **상품 사진은 자리 표시다.** 헤드폰 그림과 색 면은 실제 상품 사진 자리이고, 흰 배경 사진이 흰 카드 위에서 경계가 흐린 것은 알려진 문제로 받아들였다.
- **기능 규칙은 제품 문서에 있다.** [제품 문서 INDEX](../../../docs/product/INDEX.md)부터 읽는다.
- **흐름은 [흐름도](../wireframes/boards/FlowMap.dc.html)([png](../wireframes/shots/FlowMap.png))로 본다.** 흐름도의 화면 이름은 아래 보드 목록의 제목과 대응한다. 보드 파일 이름은 와이어프레임 보드 이름 앞에 `F`, 끝에 테마 `L`/`D`를 붙였다(예: `PurposeDetail` → `FPurposeDetailL`).

## 보드 형식

[와이어프레임 README의 보드 형식](../wireframes/README.md#보드-형식)과 같다. `boards/support.js`가 런타임이다.

## 파일

| 경로 | 내용 |
| --- | --- |
| `boards/` | 보드 HTML 104개, 런타임 `support.js` |
| `shots/` | 보드별 PNG |
| `manifest.json` | 보드 파일·제목·묶음·테마·크기·이동 대상 목록. 캔버스 `canvas.json`에서 뽑았다 |
| `capture.py` | `shots/`를 다시 찍는 스크립트 |

## 갱신

1. 보드는 [`design/canvas-fresh/`](../../canvas-fresh/README.md)의 생성기로 만든다: `for g in gen_full_home gen_category gen_purpose gen_product gen_archive gen_account; do python3 $g.py <out>; done` 후 `<out>/project/Pick*.dc.html`을 지우고 나머지를 `boards/`에 덮어쓴다.
2. 런타임은 캔버스의 `artifact-type/dc-runtime.js`를 Artifact read로 받아 `boards/support.js`로 둔다.
3. `manifest.json`은 캔버스 `canvas.json`의 `fullL`·`fullD` 페이지 보드(제목·크기·배치)와 묶음 제목 메모로 다시 만든다.
4. `python3 capture.py`로 PNG를 다시 찍는다. 로컬에서 직접 보려면 `python3 -m http.server --directory boards`로 띄운다.

## 보드 목록

캔버스 배치 순서(묶음별 왼쪽→오른쪽)다. 이동 대상은 보드 안 `href`에서 뽑았고 테마 접미사를 뗐다.

### 홈 · 처리 영역은 연속 처리

| 화면 | 제목 | 라이트 | 다크 | 이동 대상 |
| --- | --- | --- | --- | --- |
| FHome | 홈 · 할 일 접고 펼치기 | [html](boards/FHomeL.dc.html) · [png](shots/FHomeL.png) | [html](boards/FHomeD.dc.html) · [png](shots/FHomeD.png) | FCategoryHome, FHomeFillFlow, FHomeReviewFlow, FPurposeDetail, FPurposeHome, FSettings |
| FHomeReviewFlow | 홈 · 분류·목적 확인 연속 처리 | [html](boards/FHomeReviewFlowL.dc.html) · [png](shots/FHomeReviewFlowL.png) | [html](boards/FHomeReviewFlowD.dc.html) · [png](shots/FHomeReviewFlowD.png) | FHome |
| FHomeFillFlow | 홈 · 정보 보완 연속 처리 | [html](boards/FHomeFillFlowL.dc.html) · [png](shots/FHomeFillFlowL.png) | [html](boards/FHomeFillFlowD.dc.html) · [png](shots/FHomeFillFlowD.png) | FHome |
| FDuplicateCompare | 홈 · 중복 후보 비교 시트 | [html](boards/FDuplicateCompareL.dc.html) · [png](shots/FDuplicateCompareL.png) | [html](boards/FDuplicateCompareD.dc.html) · [png](shots/FDuplicateCompareD.png) | FHome |
| FDuplicateConfirmBoth | 홈 · 중복 · 둘 다 두기 확인 | [html](boards/FDuplicateConfirmBothL.dc.html) · [png](shots/FDuplicateConfirmBothL.png) | [html](boards/FDuplicateConfirmBothD.dc.html) · [png](shots/FDuplicateConfirmBothD.png) | FHome |
| FDuplicateConfirmNew | 홈 · 중복 · 새 항목 지우기 확인 | [html](boards/FDuplicateConfirmNewL.dc.html) · [png](shots/FDuplicateConfirmNewL.png) | [html](boards/FDuplicateConfirmNewD.dc.html) · [png](shots/FDuplicateConfirmNewD.png) | FHome |
| FDuplicateConfirmOld | 홈 · 중복 · 기존 항목 지우기 확인 | [html](boards/FDuplicateConfirmOldL.dc.html) · [png](shots/FDuplicateConfirmOldL.png) | [html](boards/FDuplicateConfirmOldD.dc.html) · [png](shots/FDuplicateConfirmOldD.png) | FHome |

### 카테고리

| 화면 | 제목 | 라이트 | 다크 | 이동 대상 |
| --- | --- | --- | --- | --- |
| FCategoryHome | 카테고리 · 첫 화면 | [html](boards/FCategoryHomeL.dc.html) · [png](shots/FCategoryHomeL.png) | [html](boards/FCategoryHomeD.dc.html) · [png](shots/FCategoryHomeD.png) | FHome, FPurposeHome |
| FCategoryAddSheet | 카테고리 · + 추가 시트 | [html](boards/FCategoryAddSheetL.dc.html) · [png](shots/FCategoryAddSheetL.png) | [html](boards/FCategoryAddSheetD.dc.html) · [png](shots/FCategoryAddSheetD.png) | FCategoryHome, FHome, FPurposeHome |
| FCategoryList | 카테고리 · 세부 유형 목록 | [html](boards/FCategoryListL.dc.html) · [png](shots/FCategoryListL.png) | [html](boards/FCategoryListD.dc.html) · [png](shots/FCategoryListD.png) | FCategoryHome, FHome, FProductDetail, FPurposeHome |
| FCategoryListCustom | 카테고리 · 직접 만든 카테고리 목록 (⋯ 편집·삭제) | [html](boards/FCategoryListCustomL.dc.html) · [png](shots/FCategoryListCustomL.png) | [html](boards/FCategoryListCustomD.dc.html) · [png](shots/FCategoryListCustomD.png) | FCategoryHome, FHome, FProductDetail, FPurposeHome |
| FCategoryListCustomEmpty | 카테고리 · 직접 만든 카테고리 (상품 없음) | [html](boards/FCategoryListCustomEmptyL.dc.html) · [png](shots/FCategoryListCustomEmptyL.png) | [html](boards/FCategoryListCustomEmptyD.dc.html) · [png](shots/FCategoryListCustomEmptyD.png) | FCategoryHome, FHome, FProductDetail, FPurposeHome |
| FCategoryEditSheet | 카테고리 · 편집 시트 | [html](boards/FCategoryEditSheetL.dc.html) · [png](shots/FCategoryEditSheetL.png) | [html](boards/FCategoryEditSheetD.dc.html) · [png](shots/FCategoryEditSheetD.png) | FCategoryHome, FHome, FProductDetail, FPurposeHome |
| FCategoryDeleteConfirm | 카테고리 · 삭제 확인 | [html](boards/FCategoryDeleteConfirmL.dc.html) · [png](shots/FCategoryDeleteConfirmL.png) | [html](boards/FCategoryDeleteConfirmD.dc.html) · [png](shots/FCategoryDeleteConfirmD.png) | FCategoryHome, FHome, FProductDetail, FPurposeHome |

### 목적

| 화면 | 제목 | 라이트 | 다크 | 이동 대상 |
| --- | --- | --- | --- | --- |
| FPurposeHome | 목적 · 첫 화면 | [html](boards/FPurposeHomeL.dc.html) · [png](shots/FPurposeHomeL.png) | [html](boards/FPurposeHomeD.dc.html) · [png](shots/FPurposeHomeD.png) | FArchiveList, FCategoryHome, FHome, FPurposeDetail, FPurposeDetailEmpty |
| FPurposeCreate | 목적 · 새 목적 만들기 시트 | [html](boards/FPurposeCreateL.dc.html) · [png](shots/FPurposeCreateL.png) | [html](boards/FPurposeCreateD.dc.html) · [png](shots/FPurposeCreateD.png) | FArchiveList, FCategoryHome, FHome, FPurposeDetail, FPurposeDetailEmpty, FPurposeHome |
| FPurposeDetail | 목적 · 상세 | [html](boards/FPurposeDetailL.dc.html) · [png](shots/FPurposeDetailL.png) | [html](boards/FPurposeDetailD.dc.html) · [png](shots/FPurposeDetailD.png) | FCategoryHome, FHome, FProductDetail, FPurposeHome |
| FPurposeDetailEmpty | 목적 · 빈 목적 상세 | [html](boards/FPurposeDetailEmptyL.dc.html) · [png](shots/FPurposeDetailEmptyL.png) | [html](boards/FPurposeDetailEmptyD.dc.html) · [png](shots/FPurposeDetailEmptyD.png) | FCategoryHome, FHome, FProductDetail, FPurposeHome |
| FPurposeEditInPlace | 목적 · 그 자리에서 편집 | [html](boards/FPurposeEditInPlaceL.dc.html) · [png](shots/FPurposeEditInPlaceL.png) | [html](boards/FPurposeEditInPlaceD.dc.html) · [png](shots/FPurposeEditInPlaceD.png) | FCategoryHome, FHome, FProductDetail, FPurposeHome |
| FPurposeDeleteConfirm | 목적 · 삭제 확인 | [html](boards/FPurposeDeleteConfirmL.dc.html) · [png](shots/FPurposeDeleteConfirmL.png) | [html](boards/FPurposeDeleteConfirmD.dc.html) · [png](shots/FPurposeDeleteConfirmD.png) | FCategoryHome, FHome, FProductDetail, FPurposeHome |
| FPurposeAddCandidates | 목적 · 후보 추가 시트 | [html](boards/FPurposeAddCandidatesL.dc.html) · [png](shots/FPurposeAddCandidatesL.png) | [html](boards/FPurposeAddCandidatesD.dc.html) · [png](shots/FPurposeAddCandidatesD.png) | FCategoryHome, FHome, FProductDetail, FPurposeHome |
| FPurposeAddCategoryFilter | 목적 · 후보 추가 · 카테고리로 거르기 | [html](boards/FPurposeAddCategoryFilterL.dc.html) · [png](shots/FPurposeAddCategoryFilterL.png) | [html](boards/FPurposeAddCategoryFilterD.dc.html) · [png](shots/FPurposeAddCategoryFilterD.png) | FCategoryHome, FHome, FProductDetail, FPurposeHome |
| FPurposeFinishPick | 목적 · 비교 끝내기 · 구매 상품 고르기 | [html](boards/FPurposeFinishPickL.dc.html) · [png](shots/FPurposeFinishPickL.png) | [html](boards/FPurposeFinishPickD.dc.html) · [png](shots/FPurposeFinishPickD.png) | FCategoryHome, FHome, FProductDetail, FPurposeHome |
| FPurposeFinishConfirm | 목적 · 비교 끝내기 · 아카이브 확인 | [html](boards/FPurposeFinishConfirmL.dc.html) · [png](shots/FPurposeFinishConfirmL.png) | [html](boards/FPurposeFinishConfirmD.dc.html) · [png](shots/FPurposeFinishConfirmD.png) | FCategoryHome, FHome, FProductDetail, FPurposeHome |
| FPurposeFinishDone | 목적 · 비교 끝내기 · 완료 | [html](boards/FPurposeFinishDoneL.dc.html) · [png](shots/FPurposeFinishDoneL.png) | [html](boards/FPurposeFinishDoneD.dc.html) · [png](shots/FPurposeFinishDoneD.png) | FCategoryHome, FHome, FProductDetail, FPurposeHome |

### 상품

| 화면 | 제목 | 라이트 | 다크 | 이동 대상 |
| --- | --- | --- | --- | --- |
| FProductDetail | 상품 · 상세 | [html](boards/FProductDetailL.dc.html) · [png](shots/FProductDetailL.png) | [html](boards/FProductDetailD.dc.html) · [png](shots/FProductDetailD.png) | FCategoryList, FWebView |
| FProductEdit | 상품 · 그 자리에서 편집 | [html](boards/FProductEditL.dc.html) · [png](shots/FProductEditL.png) | [html](boards/FProductEditD.dc.html) · [png](shots/FProductEditD.png) | FCategoryList, FWebView |
| FProductPurposeSheet | 상품 · 편집 · 목적 선택 시트 | [html](boards/FProductPurposeSheetL.dc.html) · [png](shots/FProductPurposeSheetL.png) | [html](boards/FProductPurposeSheetD.dc.html) · [png](shots/FProductPurposeSheetD.png) | FCategoryList, FWebView |
| FProductCategoryPicker | 상품 · 편집 · 카테고리 선택 시트 | [html](boards/FProductCategoryPickerL.dc.html) · [png](shots/FProductCategoryPickerL.png) | [html](boards/FProductCategoryPickerD.dc.html) · [png](shots/FProductCategoryPickerD.png) | FCategoryList, FWebView |
| FProductCategoryCreate | 상품 · 편집 · 새 세부 카테고리 | [html](boards/FProductCategoryCreateL.dc.html) · [png](shots/FProductCategoryCreateL.png) | [html](boards/FProductCategoryCreateD.dc.html) · [png](shots/FProductCategoryCreateD.png) | FCategoryList, FWebView |
| FProductDeleteConfirm | 상품 · 삭제 확인 (도움말 펼침) | [html](boards/FProductDeleteConfirmL.dc.html) · [png](shots/FProductDeleteConfirmL.png) | [html](boards/FProductDeleteConfirmD.dc.html) · [png](shots/FProductDeleteConfirmD.png) | FCategoryList, FWebView |
| FProductFill | 상품 · 정보 보완 필요 | [html](boards/FProductFillL.dc.html) · [png](shots/FProductFillL.png) | [html](boards/FProductFillD.dc.html) · [png](shots/FProductFillD.png) | FHome, FWebView |
| FProductFillEdit | 상품 · 정보 보완 필요 · 편집 | [html](boards/FProductFillEditL.dc.html) · [png](shots/FProductFillEditL.png) | [html](boards/FProductFillEditD.dc.html) · [png](shots/FProductFillEditD.png) | FHome, FWebView |
| FProductProcessing | 상품 · 분석 중 | [html](boards/FProductProcessingL.dc.html) · [png](shots/FProductProcessingL.png) | [html](boards/FProductProcessingD.dc.html) · [png](shots/FProductProcessingD.png) | FHome, FWebView |

### 아카이브

| 화면 | 제목 | 라이트 | 다크 | 이동 대상 |
| --- | --- | --- | --- | --- |
| FArchiveList | 아카이브 · 끝난 비교 목록 | [html](boards/FArchiveListL.dc.html) · [png](shots/FArchiveListL.png) | [html](boards/FArchiveListD.dc.html) · [png](shots/FArchiveListD.png) | FArchiveDetail, FArchiveDetailNoPurchase, FCategoryHome, FHome, FPurposeHome |
| FArchiveDetail | 아카이브 · 상세 (구매 있음) | [html](boards/FArchiveDetailL.dc.html) · [png](shots/FArchiveDetailL.png) | [html](boards/FArchiveDetailD.dc.html) · [png](shots/FArchiveDetailD.png) | FArchiveList, FCategoryHome, FHome, FPurposeHome |
| FArchiveDetailNoPurchase | 아카이브 · 상세 (구매 없음) | [html](boards/FArchiveDetailNoPurchaseL.dc.html) · [png](shots/FArchiveDetailNoPurchaseL.png) | [html](boards/FArchiveDetailNoPurchaseD.dc.html) · [png](shots/FArchiveDetailNoPurchaseD.png) | FArchiveList, FCategoryHome, FHome, FPurposeHome |
| FArchiveEdit | 아카이브 · 제목 수정 | [html](boards/FArchiveEditL.dc.html) · [png](shots/FArchiveEditL.png) | [html](boards/FArchiveEditD.dc.html) · [png](shots/FArchiveEditD.png) | FArchiveList, FCategoryHome, FHome, FPurposeHome |
| FArchiveRestoreConfirm | 아카이브 · 비교 다시 열기 확인 | [html](boards/FArchiveRestoreConfirmL.dc.html) · [png](shots/FArchiveRestoreConfirmL.png) | [html](boards/FArchiveRestoreConfirmD.dc.html) · [png](shots/FArchiveRestoreConfirmD.png) | FArchiveList, FCategoryHome, FHome, FPurposeHome |
| FArchiveDeleteConfirm | 아카이브 · 기록 삭제 확인 | [html](boards/FArchiveDeleteConfirmL.dc.html) · [png](shots/FArchiveDeleteConfirmL.png) | [html](boards/FArchiveDeleteConfirmD.dc.html) · [png](shots/FArchiveDeleteConfirmD.png) | FArchiveList, FCategoryHome, FHome, FPurposeHome |

### 로그인 · 설정 · 공유 수신 · 웹뷰

| 화면 | 제목 | 라이트 | 다크 | 이동 대상 |
| --- | --- | --- | --- | --- |
| FLogin | 로그인 안내 (첫 실행) | [html](boards/FLoginL.dc.html) · [png](shots/FLoginL.png) | [html](boards/FLoginD.dc.html) · [png](shots/FLoginD.png) | FHome, FHomeLoggedOut |
| FHomeLoggedOut | 홈 · 로그인 전 | [html](boards/FHomeLoggedOutL.dc.html) · [png](shots/FHomeLoggedOutL.png) | [html](boards/FHomeLoggedOutD.dc.html) · [png](shots/FHomeLoggedOutD.png) | FCategoryHome, FHome, FLogin, FPurposeHome, FSettingsLoggedOut |
| FSettings | 설정 | [html](boards/FSettingsL.dc.html) · [png](shots/FSettingsL.png) | [html](boards/FSettingsD.dc.html) · [png](shots/FSettingsD.png) | FLogin |
| FSettingsLoggedOut | 설정 · 로그인 전 | [html](boards/FSettingsLoggedOutL.dc.html) · [png](shots/FSettingsLoggedOutL.png) | [html](boards/FSettingsLoggedOutD.dc.html) · [png](shots/FSettingsLoggedOutD.png) | FLogin |
| FSettingsLogout | 설정 · 로그아웃 확인 | [html](boards/FSettingsLogoutL.dc.html) · [png](shots/FSettingsLogoutL.png) | [html](boards/FSettingsLogoutD.dc.html) · [png](shots/FSettingsLogoutD.png) | FLogin |
| FSettingsWebviewClear | 설정 · 웹뷰 데이터 삭제 확인 | [html](boards/FSettingsWebviewClearL.dc.html) · [png](shots/FSettingsWebviewClearL.png) | [html](boards/FSettingsWebviewClearD.dc.html) · [png](shots/FSettingsWebviewClearD.png) | FLogin |
| FShareSaved | 공유 수신 · 저장 완료 | [html](boards/FShareSavedL.dc.html) · [png](shots/FShareSavedL.png) | [html](boards/FShareSavedD.dc.html) · [png](shots/FShareSavedD.png) | - |
| FShareSavedLocal | 공유 수신 · 로그인 전 | [html](boards/FShareSavedLocalL.dc.html) · [png](shots/FShareSavedLocalL.png) | [html](boards/FShareSavedLocalD.dc.html) · [png](shots/FShareSavedLocalD.png) | - |
| FShareSavedOffline | 공유 수신 · 오프라인 | [html](boards/FShareSavedOfflineL.dc.html) · [png](shots/FShareSavedOfflineL.png) | [html](boards/FShareSavedOfflineD.dc.html) · [png](shots/FShareSavedOfflineD.png) | - |
| FWebView | 웹뷰 · 원본 링크 | [html](boards/FWebViewL.dc.html) · [png](shots/FWebViewL.png) | [html](boards/FWebViewD.dc.html) · [png](shots/FWebViewD.png) | FProductDetail |
| FWebViewShare | 웹뷰 · 공유 시트 | [html](boards/FWebViewShareL.dc.html) · [png](shots/FWebViewShareL.png) | [html](boards/FWebViewShareD.dc.html) · [png](shots/FWebViewShareD.png) | FProductDetail |
| FWebViewExternal | 웹뷰 · 외부 앱 열기 확인 | [html](boards/FWebViewExternalL.dc.html) · [png](shots/FWebViewExternalL.png) | [html](boards/FWebViewExternalD.dc.html) · [png](shots/FWebViewExternalD.png) | FProductDetail |
