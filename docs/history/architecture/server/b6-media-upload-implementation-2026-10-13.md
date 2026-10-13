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

## 검증

- 전체 회귀(`a773d2c`, 리뷰 반영 전): **494 tests, 492 통과, 2 opt-in skip**(PlaywrightRealBrowser, RealUrlPilot), 28분 37초. 실제 PostgreSQL Testcontainers 사용.
- 리뷰 반영(`e73a82f`) 대상 테스트: media·MEDIA route·local route·전체 흐름 46 통과.
- 최종 전체 회귀(`e73a82f`): **495 tests, 493 통과, 2 opt-in skip**, 26분 35초.
- 그보다 앞선 Task 7 시점의 전체 회귀(484개 중 1개 실패)는 같은 build 디렉터리에서 compile을 동시에 실행해 간섭이 있었을 수 있다. 실패 원인(`WishlistReadIndexTest`)은 위 결정대로 고쳤다.

## B7·B10·B11 인계

- **B7:** ITEM-04·07 연결은 owner → item → media를 `for update`로 잠근다(필수). 이전 media와 새 media는 id 순서로 잠근다. READY인지 확인하고, 연결한 media 행의 `updated_at`을 갱신하고, 이전 media의 `released_at`을 기록하고, `image_source = 'USER'`로 둔다. ITEM-05 삭제 때는 참조를 해제하고 `released_at`과 item `updated_at`을 갱신한다.
- **B10:** archive snapshot 참조를 `MediaReferences.referenced`에 추가한다. 외부 이미지 URL은 snapshot에 복사한다.
- **B11:** 실제 bucket을 만들고 앱 직접 PUT에 CORS가 필요 없는지 확인한다. 아울러 다음을 확인한다.
  - signBlob 권한(`iam.serviceAccounts.signBlob`)
  - URL 만료, 생성 전용 412, `x-goog-content-length-range` 강제 여부
  - signBlob 지연에 맞춘 서명 executor 크기
  - 공개 로그에 signed URL이 새지 않는지
