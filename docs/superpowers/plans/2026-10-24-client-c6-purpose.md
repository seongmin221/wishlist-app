# C6 Purpose Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 목적 탭·생성·상세 머리 접기·그 자리 편집을 두 플랫폼에 구현한다.
**Architecture:** 기존 SessionLane, FakeStore, AuthenticatedTransport, backend routing과 계정 gate를 확장한다. Presenter가 비즈니스 상태, 플랫폼이 제스처와 생명주기를 소유한다.
**Tech Stack:** Kotlin Multiplatform, Ktor, kotlin.test, Compose, SwiftUI, SKIE.
**Spec:** [C6 설계](../specs/2026-10-24-client-c6-purpose-design.md)

## Global Constraints

- 시작 `0b95e90`, #21 MERGED / #22 Draft OPEN 미병합. 서버·C7 이후 제외.
- 목적 UI 소문자 ↔ 서버 대문자 6색·8아이콘 명시 매핑.
- SUMMARY limit·cursor 생략, 진입·foreground·refresh 전체 재조회. 편집 activity 보존.
- 제출 중 잠금, 실패 초안·응답 유실 멱등 key 보존, 계정 변경 데이터 제거.
- 후속 기능 비활성 진입점 유지(사용자 답변).

## Review Focus

잘못된 wire key, 같은 version에서 변경된 목적 표시, 응답 유실 후 중복 생성, 오래된 요청의 계정 경계, 긴 이름·IME·작은 화면·시트와 목록 제스처 경합.

## Tasks

- [x] 1. 데이터 계약: PurposeCatalog.kt·PurposeRepository.kt·PurposeStyleKeys, FakeStore 목적 CRUD·receipt·활동·한도·candidate membership, PurposeMapper·RemotePurposeRepository, byPurpose와 cache, runtime binding/gate. 공통 Fake/Remote 계약과 mapper 회귀를 먼저 실패시킨 뒤 구현. 담당 data agent.
- [x] 2. Presenter: PurposeHome/Detail/FormPresenter와 domain 입력 규칙. SessionLane 기반 state 전이·멱등 생성·변경 필드 patch·충돌 복구·계정 reset 테스트 먼저 실패 확인, 구현 후 host/Native. 담당 root.
- [x] 3. Android: feature/purpose 화면·owner·route codec, 탭 데모 교체, 생성 시트 고정 footer·dirty guard, 실제 머리 시트 nested scroll, in-place 편집, 상세 후보 masonry. 공유 Presenter 공개 면을 사용하고 앱·codec/unit 검증. 담당 Android agent.
- [x] 4. iOS: Features/Purpose 화면·owner, route·탭, SKIE state 구독·close, 같은 생성·편집·목록 시트, 기존 사진 로더 재사용. XCTest·simulator build. 담당 iOS agent.
- [x] 5. 통합: host/Native suite, Android debug/release build·unit·lint, XCTest·iOS Release simulator build, tokens 검사, 양 플랫폼 L/D 화면 확인 및 수정.
- [x] 6. 검토·문서·커밋: 독립 리뷰, C5 상태 정정, docs/INDEX·category INDEX·runtime 연동 상태·KMP/플랫폼 문서·구현 검증 이력. 한국어 commit convention 준수. push·merge는 수행하지 않음.

## 실행 기록

- 2026-10-24: 저장소·제품·핸드오프·B3 계약·C5 인계 확인. GitHub에서 #21 mergedAt/mergeCommit 및 #22 OPEN/isDraft 확인.
- 구현 방식: data·Android·iOS는 파일 경계를 분리해 병렬, root는 Presenter·통합 검증. 공유 인터페이스는 spec에 고정한다.

- 독립 리뷰: entry 복귀 시 전체 SUMMARY 재조회·이름 없는 후보 제목 누락을 수정했다. multiline 설명, 폼 owner 수명, light 팔레트 stroke, 편집 panel/시트 anchor 경쟁도 화면 확인으로 보완한다.
- 사용자 답변: 후속 기능 비활성 유지, 이름 없는 후보에는 상태 문구 사용. 제품 문서에 반영.
- 중간 검증: shared host689/Native686 통과, allTests 통과, release iOS arm64 framework 링크 통과. Android debug/release unit·assemble·lint, XCTest와 simulator build는 최종 화면 수정 뒤 재실행한다.

- 최종 검증: shared `allTests` host690/Native687, Android unit debug/release225씩·assembleDebug/Release·lint0errors/27warnings, iOS XCTest255·Release simulator build, Release device framework, token생성기8개 통과. 화면29장(L/D·IME·작은 화면·편집 접힘 포함), 독립 리뷰 잔여 중요 지적 없음. 상세 증거는 [구현 기록](../../history/architecture/client/c6-purpose-implementation-2026-10-24.md).
