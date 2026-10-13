# B6 사용자 이미지 업로드 구현 이력

> 2026-10-12 착수 · 2026-10-13 구현·독립 리뷰 반영·전체 회귀 · 브랜치 `server/b6-media-upload`

## 범위와 근거

[B6 구현 순서](../../../architecture/server/mvp-api-implementation-order.md#b6--사용자-이미지)의 media 테이블·storage adapter → MEDIA-01 → 직접 byte 업로드 → MEDIA-02를 구현하고, 표시 URL과 OPS-01 media 정리를 연결했다. 정책은 [B6 제품·운영 결정](../../product-planning/mvp/decisions/b6-media-upload-policy-2026-10-12.md)을 따랐다. 결정은 8건이고, spec 리뷰 중 2건(크기 범위 header, metadata 허용 목록)을 추가로 확정했다. 설계는 [spec](../../../superpowers/specs/2026-10-12-b6-media-upload-design.md), 실행은 [계획](../../../superpowers/plans/2026-10-12-b6-media-upload.md)의 Task 1~10을 따랐다. 클라이언트 계약은 [사용자 이미지 업로드 API](../../../architecture/server/media-upload-api.md)에 있다.

**범위 밖:** 상품 연결 ITEM-04·07(B7), archive 참조(B10), 실제 bucket·signBlob 권한·URL 만료·크기 범위 header 강제 여부(B11), C8 클라이언트.

## 구현 요약

| 영역 | 결과 |
| --- | --- |
| V18 | `media_assets`(상태·선언값·검증값 CHECK, failure_reason enum, DELETED tombstone), 정리 조건별 partial index 4개, `wishlist_items.media_asset_id`(owner 복합 FK·unique). B11 이전이고 새 컬럼이 모두 null이라 일반 `create index` |
| JPEG 검사 | SOI부터 첫 EOI까지 marker 순회(fill byte·TEM·RSTn·entropy), SOF0/1/2 하나·8bit·component 1/3·SOS 64개 이하, 허용 segment APP0(썸네일 없는 JFIF)·APP2(ICC_PROFILE)·APP14(12byte Adobe), 첫 EOI 뒤 byte 거절 |
| 검증 | 크기·Content-Type → 구조 → 치수 → ImageIO decode(warning도 실패) 순, read·decode 동시 4개 |
| 저장소 | GCS V4 signed PUT(Content-Type·`x-goog-if-generation-match: 0`·`x-goog-content-length-range: 1,5242880` 서명)·1시간 GET, RPC 1회·5초. local은 HMAC 서명 URL·hard link 생성 전용 게시. 공용 서명 executor(thread 8·queue 64·대기 2초·single-flight) |
| MEDIA-01 | owner lock 아래 key·fingerprint·PENDING 10개 판정, 재전송은 만료 불변·상태별 응답, 서명 실패 503 뒤 같은 key 재시도 |
| MEDIA-02 | transaction 밖 stat·read·검증, media 행만 잠가 한 번 반영, 객체 없음·오류 시 행 재확인, FAILED는 객체 삭제 |
| 표시 | 공용 projection·목적 미리보기의 READY media join, route가 transaction 밖에서 `signAll`, `imageUrl`·`imageMediaId`, 서명 실패 시 외부 URL로 대신하지 않음, 45분 재사용 cache |
| 정리 | OPS-01 마지막 `media` 단계. 상태별 발견 → 행만 잠근 뒤 별도 문장으로 재확인하는 lease → 객체 삭제 → tombstone. DELETING lease 2분 |
| runtime | API·maintenance의 `MediaStorageConfig`(production은 `MEDIA_BUCKET` 필수), local 저장소 route는 local 설정에서만 등록 |

## 계획 대비 결정(Rulings)

- local 저장소의 생성 전용 게시는 `ATOMIC_MOVE` 대신 hard link(`Files.createLink`)를 쓴다. POSIX rename은 기존 대상을 덮어쓰기 때문이다. spec·계획에 반영했다.
- `StoredObject`는 Task 2 검증기가 먼저 필요해 `MediaModels.kt`에 두었다.
- Main API 역할의 저장소 조립은 `apiRoutes`의 필수 `media` 인자 때문에 Task 6에서 먼저 넣고, 설정 검증(`MediaStorageConfig`)은 Task 9에서 붙였다. `Main.kt` 하나가 정리와 설정을 함께 바꾸므로 Task 8·9를 한 커밋으로 묶었다.
- mapper뿐 아니라 item·홈·목적 route 함수도 서명기를 기본값 없이 받는다. 기존 테스트 17개 파일의 호출부에 `NO_MEDIA`·`MediaUrls.NONE`을 명시했다.
- `WishlistReadIndexTest`는 V14 기준선에서 공용 projection을 측정한다. B5의 V17 컬럼처럼 `media_asset_id`와 최소 `media_assets`를 측정 동안만 두고 migrate 전에 지운다.
- 정리 발견은 상태별 `union all … limit` 쿼리로 바꿨다. OR 조건 하나는 연결된 오래된 READY 전체를 매번 평가하기 때문이다(리뷰 반영).
- 파서는 알 수 없는 필드 이름을 `details.fields`에 넣는다. 정수는 18자리까지 Long으로 읽어, 5MiB를 넘는 큰 값이 형식 오류가 아니라 `MEDIA_TOO_LARGE`가 되게 했다(리뷰 반영).
- learning Q&A는 만들지 않았다. 이번 세션에 개념 질문이 없었다.

## TDD 기록

| Task | RED | GREEN |
| --- | --- | --- |
| 1 V18 | `DatabaseMigrationTest` 3건 실패(버전 목록, media 테이블·컬럼 없음) | 10 + ReadIndexRecovery 2 + MigrationRetry 3 |
| 2 JPEG | compile 실패(신규 symbol) | JpegInspector 5, Validator 4 |
| 3 저장소 | 테스트를 구현과 같은 단계에서 썼다. 구현 파일을 잠시 빼 compile 실패를 확인했다 | Gcs 2, Local 3, SigningExecutor 3 |
| 4 MEDIA-01 | compile 실패 | 6 |
| 5 MEDIA-02 | **별도 RED 미실행**(테스트 작성 직후 구현) | 6 |
| 6 route | compile 실패(`apiRoutes` 인자·route·parser 없음) | Parser 2, Routes 1, LocalRoutes 1, Exposure 1, RuntimeFlow 4 |
| 7 표시 | **별도 RED 미실행.** 대신 mapper를 되돌려 `MediaDisplayRoutesTest`가 실패하는지 확인 | Signer 5, Display 1 |
| 8·9 정리·runtime | `MaintenanceServiceTest` compile 실패 관측. 나머지는 구현과 함께 작성 | 대상 28. 첫 실행에서 helper가 FAILED 행을 CHECK 위반으로 넣던 버그 수정 |

## 독립 리뷰와 반영

구현에 참여하지 않은 reviewer가 `8318ddd..a773d2c`를 spec·계획·결정과 대조했다. 결과는 Critical 0, Important 3, Minor 13이다. spec·계획 단계의 독립 리뷰 1회와 사용자 리뷰 1회는 구현 전에 반영했다.

| 지적 | 처리 |
| --- | --- |
| APP0·APP14를 식별자 없이 통과(JFXX 썸네일에 Exif를 넣을 수 있음) | APP0는 payload 14byte·썸네일 0인 JFIF만, APP14는 12byte Adobe만. fixture 4건 |
| single-flight 예약 실패 시 합류한 대기자가 budget 끝까지 대기, close 뒤 미완료 | 예약 실패·close 때 future를 예외로 완료 |
| local 저장소 IO 예외가 저장소 오류가 아니라 500 | 모든 IO를 `MediaStorageException`으로 변환, 테스트 추가 |
| 서명 실패 로그 없음 | mediaId·예외 타입 warn |
| parser fields·큰 정수 | 위 결정대로 수정 |
| 정리 집계·발견 비용 | update 1행일 때만 집계, 상태별 쿼리 |
| 발견 → 연결 commit → lease 순서 테스트 없음 | `beforeLease` hook 경합 테스트 추가 |
| GCS size null·`0xC8` marker | 예외 처리·UNSUPPORTED |
| cache 축출이 매번 전체 스캔 | 재사용 불가 항목 제거 뒤 10%씩 일괄 축출 |
| `MEDIA_BUSY` route 수준 테스트 없음 | route 테스트 추가 |
| 큰 목록 첫 응답에서 일부 `imageUrl` null | 설계 결과로 [계약](../../../architecture/server/media-upload-api.md#표시-url)에 명시, B11에서 executor 크기 재검토 |
| upload URL 유효시간을 app 시계로 계산 | 영향이 작아 코드 주석으로 남김 |
| 요청 로그 query 누출 회귀 테스트 | 미반영. 현재 CallLogging을 설치하지 않아 query가 남지 않는다. B11 로그 점검에서 다시 본다 |

## PR 리뷰 반영 (2026-10-13)

PR #19 리뷰의 지적 10건을 코드와 대조했고, 모두 사실이었다. 테스트를 먼저 바꾸거나 더하고, 새 API는 기존 동작을 유지한 껍데기만 만들었다. 그 상태에서 48개 중 16개가 실패(RED)하는 것을 확인한 뒤 구현했다. 읽기를 gate 밖으로 뺀 수정은, 코드를 잠시 되돌려 테스트가 실패하는지도 확인했다.

| 지적 | 처리 |
| --- | --- |
| 연결된 READY가 `coalesce(released_at, ready_at)` 조건에 영구히 걸려 정리 때마다 전부 다시 평가됨 | `released_at` 대신 `unreferenced_since`를 쓴다. READY 때 기록하고, 연결 중 null, 해제 때 기록한다. partial index를 `where status='READY' and unreferenced_since is not null`로 바꿔 연결된 행이 index에 없다. EXPLAIN ANALYZE로 연결 200개 + 후보 1개에서 index가 1행만 내는지 확인한다. V18은 아직 merge·배포되지 않아 같은 PR에서 고쳤다 |
| cold 목록의 표시 서명이 공용 pool을 채워 upload intent가 503 | 업로드 executor(thread 2·queue 16)와 표시 executor(thread 8·queue 64)를 분리(`buildMediaWiring`), 응답당 새 서명 32개 상한 |
| 저장소 read 동안 decode 허가를 쥠 | read를 gate 밖으로, gate는 verify만 |
| 반영 transaction에서 만료를 다시 보지 않음 | 잠근 행의 `expired`로 410 판정(행은 PENDING 유지) |
| local acceptUpload IO가 예외 매핑 밖 | 전체를 `MediaStorageException`으로 변환, route 503 |
| 빈 PUT이 413 | `EMPTY` → 400(GCS와 같게) |
| 확인과 읽기 사이 삭제 시 500 | `readSigned`가 `Missing`을 돌려주고 route 404 |
| 정리 조건 SQL 두 벌·상수 미사용 | 상태별 조건 목록 하나에서 발견 쿼리와 lease 재확인을 만들고 `CLEANUP_GRACE`·`DELETE_LEASE` 사용 |
| ITEM-02·HOME-02 조회가 IO 블록 두 번 | 한 블록으로 합침 |
| 전체 경로 타입 표기 | import로 정리 |

**테스트 환경:** 첫 전체 회귀는 1시간 53분 걸렸고, 502개 중 3개가 실패했다. 실패한 테스트는 `MediaUploadFlowTest`, `LocalMediaRoutesTest`, 그리고 B5의 기존 `OutboxDispatcherTest`다. 세 테스트 모두 DB 시각과 host 시각을 비교한다. Podman VM 시계가 host보다 약 1시간 38분 늦었던 것이 원인이다(절전 뒤 미동기화로 보인다). VM 시계를 맞추자 세 테스트가 단독 실행에서 통과했다. 결과는 아래 최종 회귀에 기록한다.

## PR 2차 리뷰 반영 (2026-10-13)

지적 9건을 코드와 대조했고, 모두 사실이었다. 테스트를 먼저 바꾸거나 더했고, 새 API는 기존 동작을 유지한 껍데기만 만들었다. 그 상태에서 의도한 RED 5건을 확인했다(local GET 503, 오류 매핑, 큰 정수, read 한도, read 허가). 같은 실행에서 2건이 더 실패했는데, 둘 다 container 시작 실패(`ContainerLaunchException`)라 RED로 세지 않았다.

| 지적 | 처리 |
| --- | --- |
| read를 gate 밖으로 빼면서 동시 completion의 메모리가 무제한 | decode 허가(4)와 별도로 읽기 허가(8)를 둔다. 가득 차면 503 `MEDIA_BUSY`이고, 메모리 상한은 약 40MiB다. 느린 bucket은 읽기 허가만 쥐므로 decode를 막지 않는다 |
| GCS read가 요청마다 약 5MB buffer를 할당하고 복사 | stat 크기를 read 상한으로 넘긴다. buffer를 정확한 크기로 잡고, 1byte를 더 읽어 초과 여부를 확인한다. 크기가 맞으면 복사하지 않는다 |
| local 기본 URL이 실기기에서 동작하지 않음, env 설명 없음 | `server/.env.example`에 `MEDIA_BUCKET`·`MEDIA_LOCAL_DIR`·`MEDIA_LOCAL_BASE_URL`과 LAN·에뮬레이터 주소 예를 추가하고 계약 문서에도 적었다 |
| local GET이 저장소 오류 시 500 | 503 |
| 18자리를 넘는 정수가 형식 오류 | 자릿수 제한 없이 받는다. Int 범위를 넘으면 포화시켜 `MEDIA_TOO_LARGE`·`MEDIA_DIMENSIONS_EXCEEDED`가 되고, 음수는 형식 오류다 |
| 목록에 없는 오류 코드가 기본값 409 | 모든 코드를 명시한 `mediaErrorStatus`로 바꿨다. 목록에 없는 코드는 error log를 남기고 500이다. 테스트가 전체 목록을 고정한다 |
| 정리 쿼리의 `?` 개수가 5로 고정(B10 분기 추가 시 깨짐) | `DiscoveryQuery.limits` = 분기 수 + 1. 테스트가 placeholder 수와 일치하는지 확인한다 |
| insert 뒤 재조회 | `insert … returning` |
| 파서와 서비스가 `violation()`을 두 번 실행 | 파서는 형식만 보고, 값 규칙과 우선순위는 서비스(`IntentRequest.violation`)에서만 적용한다 |

## PR 3차 리뷰 반영 (2026-10-13)

리뷰 10건은 모두 사실이었다. 이 중 1·2·3번은 앞선 수정의 부작용이었다. 이번에는 해결 방식을 먼저 정했다. 1·2·3번은 사용자에게 선택지를 물어 추천안으로 정했고, 각 변경이 영향을 주는 기존 테스트를 미리 정리한 뒤 진행했다. 바꾼 테스트와 새 테스트로 의도한 RED 10건을 확인한 뒤 구현했다.

| 지적 | 처리 |
| --- | --- |
| 응답당 32개 상한 때문에 100건 목록의 2/3가 placeholder(1차 2번의 부작용) | 개수 상한을 없앴다. 응답 하나가 표시 서명을 동시에 4개씩 진행하며 2초 budget 안에서 이어 간다. 50ms 서명 100건이 한 응답에 모두 채워지고 동시 실행은 최대 4개인지 테스트한다. 서명 executor 분리는 그대로 둔다 |
| media 단계 시간 부족이 500(Scheduler 알림·재시도) | media만 deferrable 단계로 두고 `deferredSteps`에 기록한다. 객체 삭제는 남은 시간에 저장소 호출 5초가 들어갈 때만 시작한다. B5 복구 단계의 timeout은 실패로 유지한다 |
| 전체를 다운로드한 뒤에야 decode 허가를 시도(2차 1번의 부작용) | 읽기와 decode를 하나의 검증 슬롯(4개)에서 한다. 슬롯은 최대 1초 기다리고, 얻지 못하면 읽기 전에 MEDIA_BUSY다. 메모리 상한은 약 20MiB다 |
| 값 검증이 idempotency 조회보다 먼저 | key 조회 → fingerprint 비교 → 새 요청만 값 검증 |
| `createLink`의 UnsupportedOperationException | `MediaStorageException`으로 매핑(503). hard link가 필요하다는 점을 문서에 적었다 |
| local HMAC key가 process마다 바뀜 | 저장 디렉터리의 `.signing-key`에 한 번 만들고 재사용한다(재시작 뒤에도 URL 유효) |
| intent가 owner 전체 잠금 | **미반영.** media 전용 advisory lock으로 바꿔도 `media_assets`의 owner FK 검사가 `app_users`에 KEY SHARE를 걸어, owner 잠금(FOR UPDATE)과 여전히 충돌한다. 테스트로 확인했다. 풀려면 `OwnerStructureLock`을 FOR NO KEY UPDATE로 바꿔야 하는데, B2~B5의 모든 owner 경로 동시성이 바뀌므로 이번 범위에서 하지 않는다. 후속 검토 항목이다 |
| markReady/markFailed 뒤 재조회 | `returning` |
| 오류 코드가 두 곳 | `MEDIA_ERROR_STATUS` map 하나 |
| 중복 deadline 확인·떠 있는 KDoc | 정리했다 |

## 검증

- 전체 회귀(`a773d2c`, 리뷰 반영 전): **494 tests, 492 통과, 2 opt-in skip**(PlaywrightRealBrowser, RealUrlPilot), 28분 37초. 실제 PostgreSQL Testcontainers 사용.
- 리뷰 반영(`e73a82f`) 대상 테스트: media·MEDIA route·local route·전체 흐름 46 통과.
- 최종 전체 회귀(`e73a82f`): **495 tests, 493 통과, 2 opt-in skip**, 26분 35초.
- PR 리뷰 반영 뒤 전체 회귀(VM 시계 동기화 후): **502 tests, 500 통과, 2 opt-in skip**, 15분 34초.
- PR 2차 리뷰 반영 뒤 전체 회귀: **508 tests, 506 통과, 2 opt-in skip**, 41분 11초. Podman VM이 불안정해 오래 걸렸다. 대상 테스트 실행 중 container 시작 실패가 났던 `MediaIntentServiceTest`는 단독으로 다시 돌려 통과했다.
- PR 3차 리뷰 반영 뒤 전체 회귀: **512 tests, 510 통과, 2 opt-in skip**, 17분 37초.
- 그보다 앞선 Task 7 시점의 전체 회귀(484개 중 1개 실패)는 같은 build 디렉터리에서 compile을 동시에 실행해 간섭이 있었을 수 있다. 실패 원인(`WishlistReadIndexTest`)은 위 결정대로 고쳤다.

## B7·B10·B11 인계

- **B7:** ITEM-04·07 연결은 owner → item → media를 `for update`로 잠근다(필수). 이전 media와 새 media는 id 순서로 잠근다. READY인지 확인하고, 연결한 media는 `unreferenced_since = null`과 `updated_at`을 갱신하고, 이전 media는 `unreferenced_since = now`로 두고, `image_source = 'USER'`로 둔다. ITEM-05 삭제 때는 참조를 해제하고 `unreferenced_since = now`와 item `updated_at`을 갱신한다. 연결을 해제하면서 `unreferenced_since`를 빠뜨리면 그 사진은 정리되지 않고 남는다(삭제되지는 않는 안전한 쪽의 실패).
- **B10:** archive snapshot 참조를 `MediaReferences.referenced`에 추가한다. 외부 이미지 URL은 snapshot에 복사한다.
- **B11:** 실제 bucket을 만들고 앱 직접 PUT에 CORS가 필요 없는지 확인한다. 아울러 다음을 확인한다.
  - signBlob 권한(`iam.serviceAccounts.signBlob`)
  - URL 만료, 생성 전용 412, `x-goog-content-length-range` 강제 여부
  - signBlob 지연에 맞춘 서명 executor 크기
  - 공개 로그에 signed URL이 새지 않는지
