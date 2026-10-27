# C6 목적 화면 설계

> 2026-10-24 · 사용자 요청 범위에 따른 구현 설계. 시작 `client/c6-purpose`, develop `0b95e90`.

## 범위와 근거

FPurposeHome/Create/Detail/DetailEmpty/EditInPlace를 KMP → Android Compose·iOS SwiftUI로 구현한다. [로드맵](2026-10-05-client-implementation-roadmap-design.md), [제품](../../product/organize-candidates.md), [B3 확정 계약](../../architecture/server/purpose-management-api.md), `design/handoff/interactions/{screens,motion}.md`와 목적 L/D 보드가 기준이다. 서버 변경, C7 anchor·홈, C8 상품 편집, C9 후보 추가·삭제, C11 종료·archive는 제외한다. **#21은 MERGED(0b95e90), #22는 Draft OPEN으로 develop 미포함**이다. C5 전체 완료로 기록하지 않는다.

## 접근

기존 SessionLane Presenter·계정 범위 repository·runtime gate·플랫폼 owner 구조를 확장한다. 플랫폼마다 비즈니스 상태를 별도로 구현하는 대안은 계정 전환·멱등 생성·충돌 규칙이 중복되므로 채택하지 않는다. C5 사진 전환 PR #22를 가져오는 대안도 범위·기준 브랜치에 맞지 않는다. KMP가 원본·초안·로딩·오류·목록 페이지를 소유하고 플랫폼은 펼친 카드 ID, 시트 진행값, 포커스·확인창만 소유한다.

## 제품 표현

- 목록은 SUMMARY 전체(limit·cursor 생략), 진입·foreground·당겨서 갱신 때 전체 교체. 정렬은 서버 응답을 유지한다. 생성·후보 유입만 activity를 바꾸고 편집·조회·제거는 유지한다.
- 카드 -22pt 겹침, 하나만 펼침, 접힌 카드는 펼치고 열린 카드는 상세로 이동. 최초 첫 카드, 갱신 시 같은 ID 유지(없으면 첫 카드). 생성 성공은 시트를 닫고 새 빈 목적 상세로 이동한다.
- 상세는 PUR-03 머리와 ITEM-02 purposeId 후보 목록을 독립 조회한다. 카드 본문은 기존 masonry·가격·검토 표시를 재사용하되 마지막 줄은 판매처·마지막 확인 시각이다. C7 전에는 refresh 시 첫 페이지 교체, 이어 붙이기 제공.
- 머리는 고정 색 면 + 실제 목록 시트. 기본 위치는 머리 실측, 펼침은 보드 상단 108. 손잡이 탭·드래그·목록 nested scroll 연결. 진행값으로 44→36 아이콘, 28→20 이름과 위치·설명·버튼 opacity 변형. 편집 시작은 기본 위치, 260ms 편집 패널 확장, 편집 중에도 시트 접기 허용.
- 사용자 답변(2026-10-24): 이름이 없는 목적 후보의 제목은 PROCESSING일 때 `분류 중`, 그 외 `정보 보완 필요`로 표시한다. 비어 있지 않은 이름은 원문을 유지한다.
- 이름 1~40, 설명 0~200 Unicode code point. 원문 보존·중복 이름 허용. 초기 coral/heart. 색은 즉시 머리에 반영. dirty 취소·뒤로는 버리기 확인, 제출 중 닫기·입력 잠금.
- 사용자 답변(2026-10-24): 후보 추가·삭제·비교 종료·끝난 비교의 **비활성 진입점 유지**. candidateCount=0에서는 비교 종료 숨김. 서버 allowedActions에서 EDIT가 없으면 편집 비활성.
- 생성 실패 중 응답 유실 가능 오류는 같은 payload/key 재사용, 확실한 거절은 해제. 편집 충돌은 C5 기존 정책(최신 상세 재조회, 이미 요청값이면 성공, 아니면 최신값으로 교체·안내, 재조회 실패 시 초안 유지·저장 차단)을 재사용한다. 새 제품 결정으로 확정하지 않으며 후속 검토 가능하다.

## 공개 면 (플랫폼 구현 계약)

`model/PurposeCatalog.kt`: PurposeDetail(id,name,purposeDescription,colorKey,iconKey,candidateCount,membershipVersion,version,activity,createdAt,updatedAt,allowedActions), PurposeSummary(id,name,purposeDescription,colorKey,iconKey,version,candidateCount,activity,previews), PurposeCatalog(purposes,activeCount,purposeLimit,archiveSummary), PurposeActivity(at,kind), PurposePreview(itemId,imageUrl), ArchiveSummary(count,recentTitles), PurposeCommand(name,description,colorKey,iconKey), PurposePatch(name?,description:DescriptionChange,colorKey?,iconKey?), PurposeCreated(purpose,activeCount,purposeLimit).

도메인 colorKey/iconKey는 **UI 소문자 key**다. PurposeStyleKeys의 명시적 표로 CORAL/MUSTARD/PERIWINKLE/CYAN/MINT/PINK 및 HEART/HOME/PLANE/GIFT/TENT/MUSIC/STAR/BOOK를 변환한다. wire 응답의 알 수 없는 key는 원문 보존·중립 placeholder, 입력은 알려진 선택만 허용. 단순 uppercase/lowercase가 변환 계약을 대신하지 않는다.

`PurposeRepository`: summary(), detail(id), create(command,idempotencyKey), update(id,expectedVersion,patch). summary()는 SUMMARY만 제공하며 SELECT는 C8 소비 시 별도 추가한다. `ItemListRepository.byPurpose(purposeId,cursor?,limit=40)` 추가; 기존 category 경로 보존·cache decorator 적용.

`PurposeHomePresenter`: state(PurposeHomeState: signedIn,loaded,purposes,activeCount,purposeLimit,archiveCount,loading,error,refreshFailed), load(),refresh(),refreshNow(),retry().

`PurposeDetailPresenter`: state(PurposeDetailState: detail?,items,loading,itemsLoading,hasMore,appending,error?,itemsError?,appendFailed,gone), load(id),refresh(),refreshNow(),loadMore(),retry(),applyEdited(detail).

`PurposeFormPresenter`: state(PurposeFormState: mode:FormMode,phase:FormPhase,name,description,colorKey,iconKey,dirty,canSubmit,submitting,message:FormMessage?,fieldErrors:Map<String,FieldError>,result:PurposeDetail?,gone,recoveredDetail:PurposeDetail?). startCreate(),startEdit(detail),changeName(value),changeDescription(value),chooseColor(key),chooseIcon(key),submit(),retryLoad(). runtime factory는 purposeHomePresenter/purposeDetailPresenter/purposeFormPresenter()다. 성공 result는 폼 owner가 한 번 처리하고 닫는다.

## 검증

Fake/MockEngine 계약: key 조합·명시 null·query 생략·생성 receipt·30개·429·CAS·활동순·계정 격리·목적 후보 membership. Presenter: 비로그인·전체 갱신·실패 유지·계정 변경·close·중복 제출·초안 유지·충돌 복구. Android host / iOS Native 공통 suite, Android unit/build/lint, iOS XCTest/build, 두 플랫폼 L/D 화면을 검증한다. 실서버는 인증 연결 전이므로 미실행으로 구분한다.
