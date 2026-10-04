# 와이어프레임 핸드오프

[위시리스트 디자인 캔버스](https://claude.ai/artifact/DDuoeFQ7k7i9yBqY2H9qDK)의 `와이어프레임` 페이지(흑백 보드 43개)와 `흐름도`를 레포 밖 도구(Codex 등)가 읽을 수 있도록 옮긴 사본이다. 구현의 시각 기준은 [완성 화면](../screens/README.md)이고, 이 폴더는 구조와 흐름을 참고하는 용도다. 캔버스는 로그인이 필요한 비공개 아티팩트라서 링크만으로는 열 수 없다.

## 이 사본을 읽는 법

- **기준은 HTML이다.** `boards/*.dc.html`에 글자, 크기(px), 간격, 모서리, 화면 이동(`href`)이 그대로 있다. PNG는 배치를 빠르게 보는 용도다.
- **PNG는 390×844 첫 화면, 초기 상태만 찍었다.** 스크롤 아래 내용과 펼침, 시트, 연속 처리 다음 단계 같은 상태는 HTML에서 확인한다.
- **흑백은 구조만 정한다.** 회색 값은 실제 색이 아니다. 색, 서체, 바탕, 위험 색, 다크 모드는 [완성 화면](../screens/README.md)과 [디자인 결정](../../../docs/design/decisions.md)이 기준이다. 와이어프레임 이후 바뀐 화면(홈 할 일 접기, 상품 편집 시트, 비교 끝내기 단계, 신규 카테고리 등)도 완성 화면에 있다. 와이어프레임과 완성 화면·결정 문서가 다르면 완성 화면과 결정 문서를 따른다.
- **기능 규칙은 제품 문서에 있다.** [제품 문서 INDEX](../../../docs/product/INDEX.md)부터 읽는다.
- **흐름은 한 장으로 본다.** [FlowMap](boards/FlowMap.dc.html) · [png](shots/FlowMap.png)

## 보드 형식

캔버스의 Design 런타임 형식이다. `support.js`가 런타임이다.

- `<x-dc>` 안의 마크업이 화면이다. `<helmet>`은 `<head>`로 옮겨지는 서체·스타일이다.
- `<script type="text/x-dc">`의 `Component.renderVals()`가 `{{값}}`을 채운다. 예시 데이터와 상태 전이(`setState`)가 여기 있다.
- `<sc-if value="{{조건}}">`은 조건부 표시, `<sc-for list="{{목록}}" as="x">`는 반복이다. `hint-placeholder-*`는 캔버스 미리보기용이라 무시해도 된다.
- `onClick="{{핸들러}}"`는 같은 보드 안 상태 변화, `href="X.dc.html"`은 다른 화면으로의 이동이다.

## 파일

| 경로 | 내용 |
| --- | --- |
| `boards/` | 보드 HTML 43개, `FlowMap.dc.html`, 런타임 `support.js` |
| `shots/` | 보드별 PNG |
| `manifest.json` | 보드 파일·제목·묶음·크기·이동 대상 목록. 캔버스 `canvas.json`에서 뽑았다 |
| `capture.py` | `shots/`를 다시 찍는 스크립트 |

## 갱신

1. 캔버스에서 `project/canvas.json`, 와이어프레임 페이지(`page: "wire"`)와 흐름도(`page: "flow"`) 보드, `artifact-type/dc-runtime.js`를 Artifact read로 받는다.
2. 보드는 `boards/`에, 런타임은 `boards/support.js`로 덮어쓴다. `manifest.json`은 `canvas.json`의 `boards`(제목·크기)와 `notes`(페이지 묶음 제목)로 다시 만든다.
3. `python3 capture.py`로 PNG를 다시 찍는다. 로컬에서 직접 보려면 `python3 -m http.server --directory boards`로 띄운다.

## 보드 목록

캔버스 배치 순서(위→아래, 왼쪽→오른쪽)다. 이동 대상은 보드 안 `href`에서 뽑았다.

### 홈 · 처리 영역은 연속 처리

| 보드 | 제목 | 이동 대상 |
| --- | --- | --- |
| [Home](boards/Home.dc.html) · [png](shots/Home.png) | 홈 | CategoryHome, HomeFillFlow, HomeReviewFlow, PurposeDetail, PurposeHome, Settings |
| [HomeReviewFlow](boards/HomeReviewFlow.dc.html) · [png](shots/HomeReviewFlow.png) | 홈 · 분류·목적 확인 연속 처리 | Home |
| [HomeFillFlow](boards/HomeFillFlow.dc.html) · [png](shots/HomeFillFlow.png) | 홈 · 정보 보완 연속 처리 | Home |
| [DuplicateCompare](boards/DuplicateCompare.dc.html) · [png](shots/DuplicateCompare.png) | 홈 · 중복 후보 비교 시트 | Home |
| [DuplicateConfirmBoth](boards/DuplicateConfirmBoth.dc.html) · [png](shots/DuplicateConfirmBoth.png) | 홈 · 중복 · 둘 다 두기 확인 | Home |
| [DuplicateConfirmNew](boards/DuplicateConfirmNew.dc.html) · [png](shots/DuplicateConfirmNew.png) | 홈 · 중복 · 새 항목 지우기 확인 | Home |
| [DuplicateConfirmOld](boards/DuplicateConfirmOld.dc.html) · [png](shots/DuplicateConfirmOld.png) | 홈 · 중복 · 기존 항목 지우기 확인 | Home |

### 카테고리 탭

| 보드 | 제목 | 이동 대상 |
| --- | --- | --- |
| [CategoryHome](boards/CategoryHome.dc.html) · [png](shots/CategoryHome.png) | 카테고리 · 첫 화면 | Home, PurposeHome |
| [CategoryList](boards/CategoryList.dc.html) · [png](shots/CategoryList.png) | 카테고리 · 세부 유형 목록 | CategoryHome, Home, ProductDetail, PurposeHome |
| [CategoryListCustom](boards/CategoryListCustom.dc.html) · [png](shots/CategoryListCustom.png) | 카테고리 · 직접 만든 카테고리 목록 (⋯ 편집·삭제) | CategoryHome, Home, ProductDetail, PurposeHome |
| [CategoryListCustomEmpty](boards/CategoryListCustomEmpty.dc.html) · [png](shots/CategoryListCustomEmpty.png) | 카테고리 · 직접 만든 카테고리 (상품 없음) | CategoryHome, Home, ProductDetail, PurposeHome |
| [CategoryEditSheet](boards/CategoryEditSheet.dc.html) · [png](shots/CategoryEditSheet.png) | 카테고리 · 편집 시트 | CategoryHome, Home, ProductDetail, PurposeHome |
| [CategoryDeleteConfirm](boards/CategoryDeleteConfirm.dc.html) · [png](shots/CategoryDeleteConfirm.png) | 카테고리 · 삭제 확인 | CategoryHome, Home, ProductDetail, PurposeHome |
| [CategoryAddSheet](boards/CategoryAddSheet.dc.html) · [png](shots/CategoryAddSheet.png) | 카테고리 · + 추가 시트 | Home, PurposeHome |

### 목적 탭 · 만들기·편집·후보 추가

| 보드 | 제목 | 이동 대상 |
| --- | --- | --- |
| [PurposeHome](boards/PurposeHome.dc.html) · [png](shots/PurposeHome.png) | 목적 · 첫 화면 | ArchiveList, CategoryHome, Home, PurposeDetail, PurposeDetailEmpty |
| [PurposeDetail](boards/PurposeDetail.dc.html) · [png](shots/PurposeDetail.png) | 목적 · 상세 (비교 끝내기 포함, 스크롤·손잡이로 헤더 접힘) | CategoryHome, Home, ProductDetail, PurposeHome |
| [PurposeCreate](boards/PurposeCreate.dc.html) · [png](shots/PurposeCreate.png) | 목적 · 새 목적 만들기 시트 | ArchiveList, CategoryHome, Home, PurposeDetail, PurposeDetailEmpty, PurposeHome |
| [PurposeDetailEmpty](boards/PurposeDetailEmpty.dc.html) · [png](shots/PurposeDetailEmpty.png) | 목적 · 빈 목적 상세 | CategoryHome, Home, ProductDetail, PurposeHome |
| [PurposeEditInPlace](boards/PurposeEditInPlace.dc.html) · [png](shots/PurposeEditInPlace.png) | 목적 · 그 자리에서 편집 | CategoryHome, Home, ProductDetail, PurposeHome |
| [PurposeAddCandidates](boards/PurposeAddCandidates.dc.html) · [png](shots/PurposeAddCandidates.png) | 목적 · 후보 추가 시트 | CategoryHome, Home, ProductDetail, PurposeHome |
| [PurposeDeleteConfirm](boards/PurposeDeleteConfirm.dc.html) · [png](shots/PurposeDeleteConfirm.png) | 목적 · 삭제 확인 | CategoryHome, Home, ProductDetail, PurposeHome |
| [PurposeAddCategoryFilter](boards/PurposeAddCategoryFilter.dc.html) · [png](shots/PurposeAddCategoryFilter.png) | 목적 · 후보 추가 · 카테고리로 거르기 | CategoryHome, Home, ProductDetail, PurposeHome |

### 상품 상세·편집

| 보드 | 제목 | 이동 대상 |
| --- | --- | --- |
| [ProductDetail](boards/ProductDetail.dc.html) · [png](shots/ProductDetail.png) | 상품 · 상세 (⋯ → 편집하면 그 자리에서 편집 상태) | CategoryList, WebView |
| [ProductDetailFill](boards/ProductDetailFill.dc.html) · [png](shots/ProductDetailFill.png) | 상품 · 정보 보완 필요 상태 | CategoryList, WebView |
| [ProductDetailProcessing](boards/ProductDetailProcessing.dc.html) · [png](shots/ProductDetailProcessing.png) | 상품 · 분석 중 상태 | CategoryList, WebView |

### 카테고리 선택

| 보드 | 제목 | 이동 대상 |
| --- | --- | --- |
| [CategoryPicker](boards/CategoryPicker.dc.html) · [png](shots/CategoryPicker.png) | 카테고리 선택 · 바텀시트 | CategoryList, WebView |
| [CategoryCreate](boards/CategoryCreate.dc.html) · [png](shots/CategoryCreate.png) | 카테고리 선택 · 새 세부 카테고리 | CategoryList, WebView |

### 원본 링크 웹뷰

| 보드 | 제목 | 이동 대상 |
| --- | --- | --- |
| [WebView](boards/WebView.dc.html) · [png](shots/WebView.png) | 웹뷰 · 원본 링크 | ProductDetail |
| [WebViewShare](boards/WebViewShare.dc.html) · [png](shots/WebViewShare.png) | 웹뷰 · 공유 시트 | ProductDetail |
| [WebViewExternal](boards/WebViewExternal.dc.html) · [png](shots/WebViewExternal.png) | 웹뷰 · 외부 앱 열기 확인 | ProductDetail |

### 아카이브 (끝난 비교)

| 보드 | 제목 | 이동 대상 |
| --- | --- | --- |
| [ArchiveList](boards/ArchiveList.dc.html) · [png](shots/ArchiveList.png) | 아카이브 · 끝난 비교 목록 | ArchiveDetail, ArchiveDetailNoPurchase, CategoryHome, Home, PurposeHome |
| [ArchiveDetail](boards/ArchiveDetail.dc.html) · [png](shots/ArchiveDetail.png) | 아카이브 · 상세 (구매 있음, ⋯ 메뉴, 스크롤·손잡이로 헤더 접힘) | ArchiveList, CategoryHome, Home, PurposeHome |
| [ArchiveDetailNoPurchase](boards/ArchiveDetailNoPurchase.dc.html) · [png](shots/ArchiveDetailNoPurchase.png) | 아카이브 · 상세 (구매 없음) | ArchiveList, CategoryHome, Home, PurposeHome |
| [ArchiveRestoreConfirm](boards/ArchiveRestoreConfirm.dc.html) · [png](shots/ArchiveRestoreConfirm.png) | 아카이브 · 비교 다시 열기 확인 | ArchiveList, CategoryHome, Home, PurposeHome |
| [ArchiveDeleteConfirm](boards/ArchiveDeleteConfirm.dc.html) · [png](shots/ArchiveDeleteConfirm.png) | 아카이브 · 기록 삭제 확인 | ArchiveList, CategoryHome, Home, PurposeHome |

### 로그인 · 설정 · 공유 수신

| 보드 | 제목 | 이동 대상 |
| --- | --- | --- |
| [Login](boards/Login.dc.html) · [png](shots/Login.png) | 로그인 안내 (첫 실행) | Home, HomeLoggedOut |
| [HomeLoggedOut](boards/HomeLoggedOut.dc.html) · [png](shots/HomeLoggedOut.png) | 홈 · 로그인 전 | CategoryHome, Home, Login, PurposeHome, SettingsLoggedOut |
| [Settings](boards/Settings.dc.html) · [png](shots/Settings.png) | 설정 | Home, Login |
| [SettingsLogout](boards/SettingsLogout.dc.html) · [png](shots/SettingsLogout.png) | 설정 · 로그아웃 확인 | Home, Login |
| [SettingsLoggedOut](boards/SettingsLoggedOut.dc.html) · [png](shots/SettingsLoggedOut.png) | 설정 · 로그인 전 | Home, Login |
| [ShareSaved](boards/ShareSaved.dc.html) · [png](shots/ShareSaved.png) | 공유 수신 · 저장 완료 | - |
| [ShareSavedLocal](boards/ShareSavedLocal.dc.html) · [png](shots/ShareSavedLocal.png) | 공유 수신 · 로그인 전 | - |
| [ShareSavedOffline](boards/ShareSavedOffline.dc.html) · [png](shots/ShareSavedOffline.png) | 공유 수신 · 오프라인 | - |
