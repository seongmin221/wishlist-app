# B6 사용자 이미지 업로드 설계

> 2026-10-12 · **spec 승인(2026-10-13) · 구현·독립 리뷰 반영 완료** · 제품·운영 정책 확정
>
> 구현 중 변경: local 생성 전용 게시는 hard link, APP0는 썸네일 없는 JFIF·APP14는 12byte Adobe만 허용, 정리 발견은 상태별 쿼리, 파서는 알 수 없는 필드를 fields에 넣고 큰 정수를 MEDIA_TOO_LARGE로 본다. 차이는 [구현 이력](../../history/architecture/server/b6-media-upload-implementation-2026-10-13.md)에 있다.

## 목표와 범위

사진 보관함에서 고른 이미지를 앱이 저장소에 직접 올리고, 서버가 owner의 검증된 READY `mediaId`를 돌려준다. [구현 순서](../../architecture/server/mvp-api-implementation-order.md#b6--사용자-이미지)의 내부 순서인 media 테이블·storage adapter → MEDIA-01 → 직접 byte 업로드 → MEDIA-02 객체 확인을 따르고, OPS-01에 media 정리 단계를 추가한다.

[B6 제품·운영 결정](../../history/product-planning/mvp/decisions/b6-media-upload-policy-2026-10-12.md)은 2026-10-12 사용자 답변으로 확정했다.

**범위 밖:** 상품에 mediaId를 연결·해제하는 ITEM-04·ITEM-07(B7). archive snapshot의 media 참조(B10). 실제 bucket, signBlob IAM 권한, 실제 URL 만료 확인(B11). C8 클라이언트 구현. client/와 design/handoff/는 수정하지 않는다.

**통과 조건:** MIME·size·실제 이미지 검증, 다른 owner의 completion 거절, completion 재전송 idempotency, 만료·실패 뒤 재업로드, 미참조 정리와 참조 보존, OPS-01 media 단계, 실제 PostgreSQL 경합·upgrade, local 저장소로 intent → PUT → completion → 상품 응답 표시까지의 전체 흐름.

## 확정 결정 요약

| # | 결정 |
| --- | --- |
| 1 | `image/jpeg`만, 5MiB 이하, 긴 변 2048px 이하, 짧은 변 1px 이상. 서버가 실제 byte로 검증 |
| 2 | GCS private bucket에 V4 signed PUT 직접 업로드. Content-Type, `x-goog-if-generation-match: 0`, `x-goog-content-length-range: 1,5242880` 서명. 실제 권한·크기 header 강제 여부는 B11 |
| 3 | upload URL 15분. 만료 전 같은 key는 같은 mediaId에 새 URL. 객체 없음 409(PENDING 유지), 만료 410, 검증 실패는 FAILED·객체 삭제. 둘 다 새 key 필요 |
| 4 | 만료 PENDING·FAILED·미참조 READY는 참조가 없어진 때부터 24시간 뒤 OPS-01이 삭제. media 하나는 상품 하나만 참조 |
| 5 | 기존 `imageUrl`에 1시간 signed GET, `imageMediaId` 병기 |
| 6 | 외부 이미지는 URL만 보관. 깨지면 placeholder |
| 7 | 클라이언트가 EXIF 제거. 서버는 APP0·APP2(ICC_PROFILE)·APP14 외의 APPn과 COM이 어디에든 있거나 첫 EOI 뒤 byte가 있으면 FAILED |
| 8 | owner당 만료 전 PENDING 10개면 새 intent 429 |

## 현재 코드와의 차이

| 영역 | 현재 | B6 변경 |
| --- | --- | --- |
| schema | `wishlist_items.product_image_url`(외부 URL), `image_source` | `media_assets` 테이블, `wishlist_items.media_asset_id` 참조 컬럼(V18) |
| 의존성 | `google-cloud-storage:2.68.0`이 firebase-admin을 통해 이미 runtime classpath에 있음 | 같은 버전을 직접 선언. 버전 변경 없음 |
| API | MEDIA route 없음 | `POST /v1/media/upload-intents`, `POST /v1/media/{id}/completion`. local 환경에서만 local 저장소 PUT/GET route |
| 표시 | `ProductDto.imageUrl` = `product_image_url`, 목적 미리보기도 같은 컬럼 | READY media가 연결돼 있으면 signed GET URL, `imageMediaId` 추가 |
| maintenance | dispatch → reconcile → pending → budget | 마지막에 `media` 단계 추가 |
| config | API·maintenance에 저장소 설정 없음 | production은 `MEDIA_BUCKET` 필수. local은 미설정 시 local 저장소 |

## 1. 데이터 모델 (V18)

현재 마지막 migration은 `V17__analysis_metadata_and_recovery.sql`이다. 다음 번호는 V18(`V18__media_assets.sql`)이다. 운영 배포가 B11 이전이고 새 컬럼이 모두 null이므로, B5처럼 transaction 안에서 일반 index를 만든다. V16의 concurrently 방식은 쓰지 않으며 이 근거를 이력에 남긴다.

```sql
create table media_assets (
    id uuid primary key,
    owner_id uuid not null references app_users(id),
    idempotency_key uuid not null,
    request_fingerprint char(64) not null,
    object_key text not null unique,
    content_type varchar(32) not null,
    declared_byte_size integer not null,
    declared_width integer not null,
    declared_height integer not null,
    status varchar(16) not null default 'PENDING',
    byte_size integer, width integer, height integer,
    failure_reason varchar(32),
    upload_expires_at timestamptz not null,
    ready_at timestamptz, failed_at timestamptz, released_at timestamptz, deleted_at timestamptz,
    created_at timestamptz not null default clock_timestamp(),
    updated_at timestamptz not null default clock_timestamp(),
    unique (owner_id, idempotency_key),
    unique (owner_id, id),
    constraint media_assets_status_check check (status in ('PENDING','READY','FAILED','DELETING','DELETED')),
    constraint media_assets_content_type_check check (content_type = 'image/jpeg'),
    constraint media_assets_declared_check check (declared_byte_size between 1 and 5242880
        and declared_width between 1 and 2048 and declared_height between 1 and 2048),
    constraint media_assets_ready_check check (status <> 'READY'
        or (byte_size is not null and width is not null and height is not null and ready_at is not null)),
    constraint media_assets_failed_check check (status <> 'FAILED' or (failure_reason is not null and failed_at is not null)),
    constraint media_assets_failure_reason_check check (failure_reason is null or failure_reason in ('SIZE_MISMATCH',
        'CONTENT_TYPE_MISMATCH','NOT_JPEG','UNSUPPORTED_JPEG','METADATA_PRESENT','DIMENSIONS_EXCEEDED',
        'DIMENSIONS_MISMATCH','DECODE_FAILED')),
    constraint media_assets_deleted_check check (status <> 'DELETED' or deleted_at is not null)
);
create index media_assets_pending_owner_idx on media_assets (owner_id, upload_expires_at) where status = 'PENDING';
-- 정리 발견 조건마다 partial index를 둔다. READY는 연결 여부와 무관하게 남지만 기준 시각으로 범위를 좁힌다.
create index media_assets_cleanup_pending_idx on media_assets (upload_expires_at, id) where status = 'PENDING';
create index media_assets_cleanup_failed_idx on media_assets (failed_at, id) where status = 'FAILED';
create index media_assets_cleanup_ready_idx on media_assets ((coalesce(released_at, ready_at)), id) where status = 'READY';
create index media_assets_cleanup_deleting_idx on media_assets (updated_at, id) where status = 'DELETING';

alter table wishlist_items add column media_asset_id uuid;
alter table wishlist_items add constraint wishlist_items_media_owner_fk
    foreign key (owner_id, media_asset_id) references media_assets(owner_id, id);
create unique index wishlist_items_media_asset_unique on wishlist_items (media_asset_id) where media_asset_id is not null;
```

- **멱등 key는 media 행에 보관한다.** `mutation_receipts`는 대상 테이블로 FK를 거는 구조이므로 media용 FK 컬럼을 늘리지 않고 `unique(owner_id, idempotency_key)`로 같은 의미를 얻는다. 기존 receipt처럼 계정 수명 동안 key를 유지하려고, 정리 작업은 행을 지우지 않고 `DELETED` tombstone으로 남긴다. 그래서 오래된 key를 재전송해도 새 media가 생기지 않는다.
- **owner 복합 FK.** 상품은 같은 owner의 media만 참조할 수 있다. B7의 연결 검증 외에 DB도 다른 owner 참조를 막는다.
- **unique 참조.** 한 media는 상품 하나만 참조한다(결정 4). 상품 행이 `DELETED` lifecycle이어도 컬럼이 남아 있으면 unique에 걸리므로, B7의 ITEM-05는 삭제 때 참조를 비우고 `released_at`을 기록해야 한다(B7 인계).
- `released_at`은 B7이 연결을 해제할 때 기록한다. B6에는 이 값을 쓰는 경로가 없고, 정리 판정과 테스트 fixture만 이 값을 읽는다.
- 시각은 모두 DB `clock_timestamp()`다.

**상태 전이.**

```text
PENDING --completion 성공--> READY --정리(미참조 24h)--> DELETING --> DELETED
PENDING --검증 실패--> FAILED --정리(24h)--> DELETING --> DELETED
PENDING --만료 후 24h--> DELETING --> DELETED
```

만료는 상태가 아니라 `status = 'PENDING' and upload_expires_at <= now`로 판정한다. READY와 FAILED는 다시 PENDING으로 돌아가지 않는다.

## 2. 저장소 adapter

```kotlin
data class UploadTarget(val method: String, val url: String, val headers: Map<String, String>, val expiresAt: Instant)
data class StoredObject(val byteSize: Long, val contentType: String?)
data class SignedRead(val url: String, val expiresAt: Instant)
fun interface MediaUrlSigner { fun sign(objectKey: String): SignedRead? }   // 실패·시간 초과 시 null

interface MediaStorage {
    fun uploadTarget(objectKey: String, contentType: String, expiresAt: Instant): UploadTarget
    fun readUrl(objectKey: String, validFor: Duration): SignedRead
    fun stat(objectKey: String): StoredObject?           // 없으면 null
    fun read(objectKey: String, maxBytes: Int): ByteArray // maxBytes 초과면 MediaTooLarge
    fun delete(objectKey: String)                         // 없어도 성공
}
```

- **객체 경로.** `media/{ownerId}/{mediaId}.jpg`. 내부 UUID만 쓰고 사용자 입력은 넣지 않는다.
- **`GcsMediaStorage`.** `google-cloud-storage`의 `Storage.signUrl`로 V4 서명한다. PUT은 `Content-Type: image/jpeg`, `x-goog-if-generation-match: 0`, `x-goog-content-length-range: 1,5242880`을 extension header로 서명에 포함한다. 클라이언트가 세 header를 정확히 보내지 않으면 서명이 맞지 않아 실패한다. 덮어쓰기 시도는 GCS가 412로 거부한다. 크기 범위 header를 GCS가 signed PUT에서 강제하는지는 B11에서 확인한다. 강제하지 않아도 completion의 크기 검사가 남는다. 서명 credential은 Application Default Credentials를 쓰고, Cloud Run에서는 라이브러리가 IAM signBlob을 호출한다(권한 확인은 B11). stat·read·delete는 RPC마다 총 5초 제한을 두고 재시도하지 않는다. 기존 Cloud Tasks RPC 규칙과 같다.
- **`LocalMediaStorage`.** `APP_ENV=local`이고 `MEDIA_BUCKET`이 없을 때 쓴다. `MEDIA_LOCAL_DIR`(기본 `build/local-media`) 아래 파일로 보관한다. URL은 `{MEDIA_LOCAL_BASE_URL}/local/media-objects/{objectKey}?expires=…&signature=…`이고 `MEDIA_LOCAL_BASE_URL` 기본값은 `http://localhost:{PORT}`다. Android 에뮬레이터처럼 다른 주소로 접근하는 기기는 이 값을 지정한다. signature는 process 시작 때 만든 HMAC key로 만든다. 같은 세 header를 요구하고 body가 1~5,242,880 byte 범위를 벗어나면 413이다. body는 같은 디렉터리의 임시 파일에 쓴 뒤 hard link(`Files.createLink`)로 게시하고, 대상이 이미 있으면 412다. POSIX의 `ATOMIC_MOVE`(rename)는 기존 대상을 덮어쓰므로 생성 전용 조건에 쓰지 않는다(구현 중 변경). 그래서 completion이 쓰는 중인 파일을 보지 않고, 동시 PUT 두 개 중 하나만 성공한다. 테스트는 in-memory 구현을 주입한다.
- **timeout.** GCS client는 `StorageOptions.setRetrySettings`(maxAttempts 1, totalTimeout 5초)와 `HttpTransportOptions`의 connect·read timeout 5초로 만든다. `read`는 `reader()`로 `maxBytes + 1`까지만 읽는다.
- **서명 실행.** Cloud Run의 서명은 credential이 직접 부르는 signBlob HTTP 호출이라 `StorageOptions`의 timeout이 적용되지 않는다. 그래서 모든 서명(upload PUT, MEDIA-02 display, 목록 표시)을 process 전체가 같이 쓰는 `MediaSigningExecutor`에서 실행한다.
  - thread 8개, queue 64개. 가득 차면 즉시 서명 실패로 처리한다.
  - 호출자는 결과를 최대 2초 기다린다. 기다리다 넘기면 호출자에게는 실패지만 실행 중인 서명은 끝까지 돌고, 표시 URL이면 결과를 cache에 넣는다. 그래서 막힌 호출이 응답마다 새로 쌓이지 않는다.
  - 같은 object key의 표시 URL 서명은 동시에 하나만 실행하고(single-flight), 다른 호출자는 그 결과를 기다린다.
  - upload 서명이 실패하거나 시간을 넘기면 503 `MEDIA_STORAGE_UNAVAILABLE`, 표시 서명이면 `imageUrl`·`display`를 null로 둔다.
- **경계.** 저장소 호출 중에는 DB transaction과 connection을 쥐지 않는다.

## 3. MEDIA-01 업로드 intent

`POST /v1/media/upload-intents`, `Idempotency-Key` 헤더(UUID, 기존 생성 API와 같은 파서).

```json
{ "contentType": "image/jpeg", "byteSize": 812345, "width": 2048, "height": 1536 }
```

**입력 검증(422).** 알 수 없는 필드와 타입 오류는 `INVALID_MEDIA_REQUEST`이고 `details.fields`를 붙인다. body의 `contentType` 필드가 `image/jpeg`가 아니면 `UNSUPPORTED_MEDIA_TYPE`이다(HTTP 요청의 Content-Type header와는 별개). `byteSize`나 치수가 0 이하이면 `INVALID_MEDIA_REQUEST`, `byteSize`가 5,242,880 초과이면 `MEDIA_TOO_LARGE`, 변이 2048 초과이면 `MEDIA_DIMENSIONS_EXCEEDED`이다. 선언값은 인코딩한 실제 byte 수와 회전을 적용한 뒤의 width·height이며, completion이 실제 객체와 정확히 비교한다(C8 계약). 여러 조건이 동시에 걸리면 `INVALID_MEDIA_REQUEST` → `UNSUPPORTED_MEDIA_TYPE` → `MEDIA_TOO_LARGE` → `MEDIA_DIMENSIONS_EXCEEDED` 순서로 첫 코드 하나만 준다. 형식 검증은 transaction 전에 한다.

**처리(한 transaction).**

1. `OwnerStructureLock.lock(owner)`.
2. `(owner, key)`로 기존 행을 찾는다. 있으면 fingerprint(`contentType`·`byteSize`·`width`·`height`의 canonical JSON SHA-256)를 비교한다. 다르면 409 `IDEMPOTENCY_KEY_REUSED`이고, 같으면 재전송이다(아래 표).
3. 새 요청이면 만료 전 PENDING 수를 센다. 10개 이상이면 429 `MEDIA_UPLOAD_LIMIT_REACHED`이고, `Retry-After`는 가장 이른 만료까지 남은 초(최소 1)다.
4. 행을 insert한다. `upload_expires_at = clock_timestamp() + 15분`. commit.
5. transaction 밖에서 `uploadTarget(objectKey, contentType, upload_expires_at)`로 서명한다. 서명이 실패하면 503 `MEDIA_STORAGE_UNAVAILABLE`이고 행은 남는다. 같은 key로 다시 요청하면 재전송 경로가 다시 서명한다. 남은 PENDING 행은 한도 10개 중 하나를 차지하므로, 앱은 503에 반드시 **같은 key**로 재시도한다(C8 계약). 새 key로 재시도하면 최대 15분 동안 429가 날 수 있다.

**재전송.**

| 기존 행 | 응답 |
| --- | --- |
| PENDING, 만료 전 | 200 + `Idempotency-Replayed: true`, 같은 mediaId, 새로 서명한 URL. **만료 시각은 처음 값을 유지한다.** 재전송으로 유효기간이 늘어나지 않는다 |
| PENDING, 만료 후 | 410 `MEDIA_UPLOAD_EXPIRED` |
| READY | 200 + replay header, `status: READY`와 `display`, `upload: null` |
| FAILED | 422 `MEDIA_INVALID_IMAGE` + `details.reason`(저장된 사유) |
| DELETING, DELETED | 410 `MEDIA_UPLOAD_EXPIRED` |

**응답(201).** 단건 GET route가 없으므로 `Location`을 붙이지 않는다.

```json
{
  "media": { "id": "…", "status": "PENDING", "contentType": "image/jpeg",
             "byteSize": 812345, "width": 2048, "height": 1536, "expiresAt": "…" },
  "upload": { "method": "PUT", "url": "…",
              "headers": { "Content-Type": "image/jpeg", "x-goog-if-generation-match": "0",
                           "x-goog-content-length-range": "1,5242880" },
              "expiresAt": "…" },
  "display": null,
  "limits": { "contentTypes": ["image/jpeg"], "maxBytes": 5242880, "maxLongEdge": 2048 }
}
```

`media.byteSize`·`width`·`height`는 PENDING에서는 선언값이고 READY에서는 검증값이다. local 저장소의 `headers`도 같은 세 header를 쓴다. 클라이언트는 받은 header를 그대로 보낸다.

## 4. 직접 byte 업로드

앱이 `upload.url`에 `upload.method`와 `upload.headers`로 body를 보낸다. 서버 API는 production에서 이 단계에 관여하지 않는다. local에서는 `PUT /local/media-objects/{objectKey...}`와 `GET` route가 서명·만료·header·생성 전용 조건을 확인한다. 이 route는 `LocalMediaStorage`를 쓰는 API 역할에서만 등록하며, `MEDIA_BUCKET`이 있거나 production이면 등록하지 않는다(404 테스트로 고정). 서명 위조·만료·header 누락은 403, 이미 있는 객체는 412, 5MiB를 넘는 body는 413이다(5MiB + 1 byte까지만 읽는다).

## 5. MEDIA-02 업로드 완료 확인

`POST /v1/media/{id}/completion`, body 없음. 경로 id 파싱이 실패하면 400 `INVALID_MEDIA_ID`이다.

1. **조회(잠금 없음).** `owner_id = owner and id = ?`. 없으면 404 `MEDIA_NOT_FOUND`이다. 다른 owner의 media도 같은 404다.
2. **상태별 즉시 응답.** READY는 200 재응답(새 display URL)이다. FAILED는 422 `MEDIA_INVALID_IMAGE` + 저장된 사유다. DELETING·DELETED와 만료된 PENDING은 410 `MEDIA_UPLOAD_EXPIRED`이다. FAILED도 PENDING 한도에서 빠지므로, 410이나 422를 받은 앱은 새 key로 intent를 만들어 다시 올린다.
3. **객체 확인(transaction 밖).** `stat` 결과가 null이면 409 `MEDIA_OBJECT_MISSING`이고 PENDING을 유지한다. 저장소 오류는 503 `MEDIA_STORAGE_UNAVAILABLE`이고 역시 PENDING을 유지한다.
4. **검증(transaction 밖).** 아래 순서로 처음 실패한 사유 하나를 고른다.

   | 사유 | 조건 |
   | --- | --- |
   | `SIZE_MISMATCH` | 객체 크기 ≠ 선언 `byteSize`, 또는 5MiB 초과(이때는 byte를 읽지 않음) |
   | `CONTENT_TYPE_MISMATCH` | 객체 Content-Type ≠ `image/jpeg` |
   | `NOT_JPEG` | SOI(`FFD8`)로 시작하지 않음, marker 구조가 깨짐(길이가 파일 밖, 알 수 없는 marker), SOF가 없음, marker 순회가 EOI를 만나기 전에 파일이 끝남 |
   | `UNSUPPORTED_JPEG` | SOF0(baseline)·SOF1(extended)·SOF2(progressive) 외의 SOF, SOF가 둘 이상, precision이 8이 아님, component 수가 1·3이 아님(CMYK 등), SOS가 64개 초과 |
   | `METADATA_PRESENT` | APP0·APP2(`ICC_PROFILE` 식별자)·APP14 외의 APPn(APP1 Exif·XMP, `MPF` 식별자의 APP2, APP13 IPTC 등)이나 COM이 어디에든 있음, 또는 처음 만난 EOI 뒤에 byte가 1개라도 있음 |
   | `DIMENSIONS_EXCEEDED` | SOF 치수가 긴 변 2048 초과 또는 0 |
   | `DIMENSIONS_MISMATCH` | SOF 치수 ≠ 선언 width·height |
   | `DECODE_FAILED` | ImageIO decode 예외 또는 decode warning(데이터 조기 종료 등) |

   marker 검사는 직접 만든 `JpegInspector`가 SOI부터 **marker 순회로 처음 만난 EOI**까지 모든 marker를 순회해 한다. MPF 보조 이미지도 `FFD9`로 끝나므로 "파일 마지막 2 byte"로 EOI를 판정하지 않는다.
   - progressive JPEG는 scan 사이에도 APPn을 둘 수 있으므로 SOS에서 멈추지 않는다.
   - marker 앞의 연속된 `FF` fill byte는 건너뛴다.
   - 길이가 없는 marker인 TEM(`FF01`)과 RSTn(`FFD0`~`FFD7`)은 2 byte만 소비한다.
   - entropy 구간에서는 `FF00`(byte stuffing)과 RSTn을 데이터로 보고, 그 밖의 `FFxx`를 다음 marker로 본다.
   - SOF가 정확히 하나여야 치수 확인 대상과 실제 decode 대상이 같아진다. 치수를 확인한 뒤에만 전체 decode를 하므로 메모리 폭탄을 막는다. SOS 64개 상한은 아주 작은 scan 수천 개로 libjpeg의 CPU를 오래 쓰게 하는 progressive 폭탄을 막는다. 정상 인코더의 progressive scan은 10개 안팎이다. decode는 `ImageReader`로 하고 `addIIOReadWarningListener`의 warning도 실패로 본다. JDK reader는 잘린 entropy 데이터에 예외 대신 warning을 내기 때문이다. `ImageIO.setUseCache(false)`로 디스크 cache를 쓰지 않는다. byte read와 검증만 process당 동시 4개로 제한하고(stat은 제외), 넘으면 503 `MEDIA_BUSY`와 `Retry-After: 1`이다.
5. **반영(짧은 transaction).** media 행을 `for update`로 잠그고 다시 확인한다. 여전히 PENDING이면 성공 시 READY(`byte_size`·`width`·`height`·`ready_at`)로, 실패 시 FAILED(`failure_reason`·`failed_at`)로 바꾼다. 그 사이 다른 completion이 먼저 반영했다면 그 결과로 응답한다. 객체는 생성 전용이라 같은 byte를 보므로 두 요청의 판정이 같다. 다른 요청이 FAILED를 반영하고 객체를 먼저 지워 3·4단계의 stat·read가 객체 없음이나 오류를 만나면, 409·503으로 답하기 전에 행을 다시 읽어 PENDING이 아니면 저장된 상태로 응답한다.
6. **FAILED 정리.** commit 뒤 `delete(objectKey)`를 한 번 시도한다. 실패하면 로그만 남기고, 7절 정리 작업이 다시 지운다.

**재검증 시각.** 만료 판정은 1단계 조회 시점의 DB 시각으로 한다. 업로드가 만료 직전에 끝나고 completion이 만료 뒤에 오면 410이다(결정 3). 이 경우 앱은 새 key로 다시 업로드한다.

**응답(200).**

```json
{
  "media": { "id": "…", "status": "READY", "contentType": "image/jpeg",
             "byteSize": 812345, "width": 2048, "height": 1536, "expiresAt": null },
  "upload": null,
  "display": { "url": "…", "expiresAt": "…" },
  "limits": { … }
}
```

MEDIA-01과 같은 `MediaDto` 모양을 쓴다. 결정 5의 MEDIA-02 표시 URL은 `display.url`이다. 표시 URL 서명이 실패하면 `display`는 null이고 응답은 200이다. READY 반영은 이미 끝났고, 이미지 표시는 이후 item 응답에서 다시 얻을 수 있기 때문이다. completion은 상품 정보를 바꾸지 않는다.

## 6. 표시 URL

- **item 응답.** `WishlistItemRowMapper`의 공용 projection에 `left join media_assets m on m.owner_id = i.owner_id and m.id = i.media_asset_id and m.status = 'READY'`를 추가해 `m.id`와 `m.object_key`를 읽는다. READY media가 있으면 `ProductDto.imageUrl`은 signed GET URL이고 `imageMediaId`는 media id다. 없으면 기존처럼 `product_image_url`이고 `imageMediaId`는 null이다. READY media가 있는데 서명이 실패하면 `imageUrl`은 null이고 `imageMediaId`는 그대로 준다. 이때 외부 URL로 대신하지 않는다. 사용자가 고른 사진 대신 다른 이미지가 보이지 않게 하기 위해서다. ITEM-01 생성·재전송, ITEM-02·03, HOME-02 카드, HOME-01 `actionGroups[].previews` 카드가 같은 mapper(`WishlistItemViewMapper`, `ReadWindowViewMapper.card`, `HomeViewMapper`)를 쓴다. ITEM-01 재전송은 현재 item을 돌려주므로 B7에서 사진을 연결한 뒤에는 media가 있다. 그래서 생성·재전송·상세 세 경로 모두 서명한다.
- **목적 미리보기.** `PurposeRepository.previews`도 같은 join을 쓰고 `PurposePreviewDto`에 `imageMediaId`를 추가한다. 이 함수는 PUR-01 SUMMARY와 HOME-01 `recentPurposes`가 쓴다. PUR-03 상세에는 미리보기가 없다.
- **서명 위치.** repository는 object key만 돌려준다. route가 DB 작업을 마친 뒤 IO dispatcher에서 응답에 필요한 key를 모아 `signAll(keys)`로 한 번에 서명하고, mapper에는 key → URL 조회표(`MediaUrls`)만 넘긴다. mapper의 조회표 인자는 **기본값 없이** 받는다. 새 호출 지점이 서명을 빠뜨리면 compile error가 나게 해서, 에러 없이 이미지만 사라지는 일을 막는다. 서명 중에는 connection을 쥐지 않는다.
- **응답별 서명 budget.** cache에 없는 key는 2절의 `MediaSigningExecutor`에서 서명하고, 응답 하나의 서명 전체에 2초 상한을 둔다. 상한을 넘긴 key는 서명 실패와 같이 처리하되, 늦게 끝난 결과는 cache에 들어가 다음 조회에서 쓰인다. ITEM-02의 `limit`이 최대 100이라 cache가 빈 상태에서 signBlob을 순차로 부르면 응답이 길어지기 때문이다.
- **`CachingMediaUrlSigner`.** 1시간짜리 URL을 발급하고 process cache에 넣는다. 남은 시간이 45분 이상이면 같은 URL을 재사용하므로 응답 URL의 남은 유효시간은 45~60분이다. 읽기 계약에도 이 범위를 적는다. 목록 하나가 signBlob을 수십 번 호출하지 않게 하기 위해서다. cache는 최대 10,000개이고, 넘으면 만료가 가장 이른 항목부터 버린다.
- **서명 실패.** 해당 이미지의 `imageUrl`만 null로 두고 나머지 응답은 정상으로 준다. 로그는 mediaId와 예외 타입만 남긴다. 클라이언트는 placeholder를 표시하고 다음 조회에서 다시 얻는다.
- 외부 이미지 URL(`product_image_url`)은 서명하거나 복사하지 않는다(결정 6).

## 7. OPS-01 media 정리

`MaintenanceService`에 마지막 단계 `media`를 추가한다. 기존 단계처럼 실패를 격리하고 공통 deadline(50초)을 따른다. budget 다음에 실행하므로 외부 호출이 느려도 앞 단계를 막지 않는다.

**발견(잠금 없음, DB 시각, batch 50, `updated_at, id` 순).**

```text
(status = 'PENDING'  and upload_expires_at <= now - 24h)
or (status = 'FAILED' and failed_at <= now - 24h)
or (status = 'READY'  and coalesce(released_at, ready_at) <= now - 24h and not referenced(m))
or (status = 'DELETING' and updated_at <= now - 2분)
referenced(m) = exists (select 1 from wishlist_items i where i.owner_id = m.owner_id and i.media_asset_id = m.id
                        and (i.lifecycle_status in ('ACTIVE', 'ARCHIVED') or i.updated_at > now - 24h))
```

`referenced`는 `MediaReferences` 한 곳에 SQL 조각으로 둔다. B10은 여기에 archive snapshot 참조를 더한다. `DELETED` lifecycle 상품의 참조는 상품이 바뀐 지 24시간이 지나면 보존하지 않는다. B7이 삭제 때 `released_at` 기록을 빠뜨려도 "참조가 없어진 때부터 24시간"이 지켜지게 하는 방어 조건이다.

`DELETING`의 `updated_at`은 삭제 시도 lease 시작 시각이다. 1단계에서 갱신한 뒤 2분 동안은 다른 maintenance가 같은 행을 다시 집지 않는다. 그래서 동시 실행 두 개가 같은 객체에 delete를 두 번 부르지 않는다. 2분은 delete RPC 5초보다 충분히 길고, 실행이 중간에 죽어도 다음 실행이 이어받게 한다.

**후보마다.**

1. 짧은 transaction에서 **두 문장으로** 잠그고 확인한다.
   - 먼저 `select … from media_assets where id = ? for update skip locked`로 행만 잠근다. 잠겨 있으면 건너뛴다.
   - 그다음 **별도 문장**으로 상태·시각 조건과 `referenced(m)`을 다시 확인한다. 통과하면 `status = 'DELETING'`, `updated_at = now`로 바꾸고 commit한다.
   - 한 문장(`select … where … and not exists(…) for update skip locked`)으로 잠그고 확인하면 안 된다. PostgreSQL은 잠금을 기다린 뒤 잠근 행의 조건만 다시 평가하고 subquery는 처음 snapshot으로 둔다. 그래서 그 사이 B7이 연결하고 commit해도 "미참조"로 보고 DELETING을 만들 수 있다. READ COMMITTED에서는 문장마다 새 snapshot을 쓰므로, 잠근 뒤 실행한 둘째 문장은 B7의 commit을 본다. 이 구간은 latch 테스트로 재현하기 어려우므로 구조로 강제하고, 코드 주석과 리뷰 항목으로 고정한다.
2. transaction 밖에서 `delete(objectKey)`를 호출한다. 객체가 없어도 성공이다.
3. 성공하면 `status = 'DELETED'`, `deleted_at = now`로 바꾼다(조건 `status = 'DELETING'`). 실패하면 그대로 두고, lease가 끝난 뒤 다음 실행에서 다시 시도한다.

**B7 연결과의 경합.** B7의 연결은 owner → item → media 순서로 잠그고, media를 반드시 `for update`로 잠근 뒤 READY일 때만 연결한다. READY를 잠그지 않고 읽기만 하면 "DELETING인데 참조" 상태가 생길 수 있다. 추가 방어로 연결할 때 media 행의 `updated_at`도 갱신한다. 그러면 정리 쪽이 어떤 방식으로 잠그더라도 행 자체가 바뀌어 조건 재평가 대상이 된다. 정리 작업은 media 하나만 잠근다. 정리가 먼저 잠그면 연결은 DELETING을 보고 거절되고(B7의 `MEDIA_NOT_READY`), 연결이 먼저 잠그면 정리는 건너뛴다. 잠금을 하나만 쥐므로 deadlock이 생기지 않는다.

**report.** `MaintenanceReport`에 `mediaDeleted`, `mediaDeleteFailed`를 추가한다. 단계 이름은 `media`다.

## 8. runtime 설정

| 역할 | 저장소 사용 | env |
| --- | --- | --- |
| api | intent 서명, completion stat·read·delete, 표시 URL 서명 | production은 `MEDIA_BUCKET` 필수. local은 선택(없으면 local 저장소, `MEDIA_LOCAL_DIR`·`MEDIA_LOCAL_BASE_URL`) |
| maintenance | 정리 delete | api와 같음 |
| general/browser worker | 없음 | 없음 |

`module()`은 테스트 주입점 `mediaStorage: MediaStorage? = null`을 추가한다. `GcsMediaStorage`의 `Storage` client는 `RuntimeResources`가 소유하고 종료 때 닫는다.

API 역할의 `module()`은 항상 Firebase를 초기화하므로 테스트에서 띄울 수 없다. 그래서 기존 테스트처럼 `routing { apiRoutes(…) }`를 직접 등록해 검증한다. `apiRoutes`는 `media: MediaWiring` 인자를 **필수로** 받는다. `MediaWiring(service: MediaService, signer: MediaUrlSigner)`이고, MEDIA route와 item·목적·홈 route가 같은 signer를 쓴다. local 저장소 route(`localMediaRoutes(storage: LocalMediaStorage)`)는 `apiRoutes` 밖에서 따로 등록한다. 기존 `AnalysisRuntimeFlowTest`·`ApiRouteExposureTest`는 in-memory 저장소로 만든 wiring을 넘기도록 고친다. local 저장소 route를 등록할지는 `RuntimeConfig`의 media 설정만 보는 순수 함수 `MediaStorageConfig.exposesLocalRoutes`로 정하고, 노출 테스트는 이 함수와 route 등록 조합으로 확인한다.

## 9. 오류·관측

| status | code | 경우 |
| --- | --- | --- |
| 400 | `INVALID_IDEMPOTENCY_KEY`, `INVALID_MEDIA_ID` | header·경로 형식 |
| 401 | `UNAUTHORIZED` | 인증 없음 |
| 404 | `MEDIA_NOT_FOUND` | 없음·다른 owner |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 같은 key·다른 요청 |
| 409 | `MEDIA_OBJECT_MISSING` | 객체 아직 없음(PENDING 유지) |
| 410 | `MEDIA_UPLOAD_EXPIRED` | 만료·정리됨 |
| 422 | `INVALID_MEDIA_REQUEST`, `UNSUPPORTED_MEDIA_TYPE`, `MEDIA_TOO_LARGE`, `MEDIA_DIMENSIONS_EXCEEDED` | intent 입력 |
| 403·412·413 | (local 저장소 route만) | 서명 위조·만료·header 누락 / 이미 있는 객체 / 5MiB 초과 body |
| 422 | `MEDIA_INVALID_IMAGE` + `details.reason` | 실제 이미지 검증 실패(FAILED) |
| 429 | `MEDIA_UPLOAD_LIMIT_REACHED` + `Retry-After` | PENDING 10개 |
| 503 | `MEDIA_STORAGE_UNAVAILABLE`, `MEDIA_BUSY` + `Retry-After` | 저장소 장애·검증 포화 |

- 로그는 mediaId, 단계 이름, 예외 타입만 남긴다. signed URL, object key의 query, 예외 메시지는 남기지 않는다. 기존 Ktor 요청 로그에 query string이 남지 않는지 local 저장소 route 테스트로 확인한다.
- 응답 본문의 signed URL은 일반 로그 대상이 아니다. B11에서 공개 로그 누출을 다시 확인한다.

## 10. 검증

테스트를 먼저 작성하고 미구현 때문에 실패하는지 확인한다. 실제 PostgreSQL(Testcontainers) 테스트를 실행하지 못하면 해당 작업을 통과로 보지 않는다.

| 영역 | 주요 사례 |
| --- | --- |
| upgrade | V17 데이터 → V18, 기존 item의 `media_asset_id` null, CHECK·FK·unique 위반 거부, 다른 owner media 참조 FK 거부 |
| JPEG 검사 | 정상 2048×1536, 1×1, grayscale, progressive, APP2 ICC_PROFILE·APP14 허용, SOI 없음, PNG byte, EOI 없음, SOS 뒤에서 잘린 파일(decode warning), APP1 Exif·XMP, APP2 MPF, APP13, COM, progressive scan 사이의 APP1, 첫 EOI 뒤 trailing byte(뒤에 다시 FFD9로 끝나는 보조 이미지 포함), marker 앞 FF fill byte, TEM·RSTn, 2049px, 선언 치수 불일치, SOF 0, SOF3·SOF9, SOF 2개, 4 component(CMYK)·12bit precision, SOS 65개 |
| storage adapter | GCS 서명 URL에 `X-Goog-SignedHeaders`(content-type·host·x-goog-content-length-range·x-goog-if-generation-match)와 15분 만료가 들어감(offline 테스트 credential), local 저장소의 서명 위조·만료·header 누락 403·덮어쓰기 412·크기 초과 413·동시 PUT 2개 중 하나만 성공 |
| MEDIA-01 | 201 응답 모양, 입력 오류 코드별 422와 동시 위반 우선순위, 서명 2초 초과 503, 재전송 표 전체, fingerprint 불일치 409, 재전송 시 만료 시각 불변, PENDING 10개 429와 Retry-After, 만료된 PENDING은 한도에서 제외, 서명 실패 503 뒤 재전송 성공 |
| MEDIA-02 | 성공 READY, 다른 owner 404, 객체 없음 409(PENDING 유지), 저장소 오류 503, 만료 410, DELETING·DELETED 410, 검증 실패 사유별 422·FAILED·객체 삭제, FAILED 재전송 같은 422, READY 재전송 200, 동시 completion 2개 → READY 하나, 한쪽 FAILED 반영·객체 삭제 뒤 다른 쪽이 저장된 422로 응답, 동시 검증 포화 503, 서명 실패 시 display null |
| 재업로드 | 410 뒤 새 key intent → PUT → READY, FAILED 뒤 새 key → READY, FAILED는 PENDING 한도에서 제외 |
| 표시 | READY media가 연결된 item의 ITEM-01 재전송·ITEM-02·03, HOME-02, HOME-01 actionGroups 카드의 `imageUrl`·`imageMediaId`, 목적 미리보기 PUR-01 SUMMARY·HOME-01 recentPurposes, 외부 URL 상품은 기존 값, 서명 cache 재사용과 45분 기준, 서명 실패 시 해당 이미지만 null(외부 URL로 대신하지 않음), 느린 signer에서 응답 서명 2초 상한, 늦게 끝난 서명의 cache 반영, 같은 key 동시 서명 1회(single-flight), executor 포화 시 즉시 실패 |
| 정리 | 만료 PENDING·FAILED·미참조 READY 24시간 경계(23h59m 보존, 24h 삭제), ACTIVE·ARCHIVED 참조 보존, DELETED lifecycle 참조는 상품 변경 24시간 뒤 정리, `released_at` 기준, delete 실패 → DELETING 유지 → lease 2분 뒤 재시도, lease 안의 DELETING은 건너뜀, tombstone 뒤 같은 key MEDIA-01 410 |
| 경합(PostgreSQL) | 동시 maintenance 2개가 같은 media를 한 번만 삭제, 정리와 연결(B7 연결 SQL을 흉내 낸 fixture) 경합 시 둘 중 하나만 성공(정리가 잠금을 기다리는 동안 연결이 commit하는 순서 포함), 동시 intent 2개(같은 key)가 행 하나 |
| maintenance | media 단계 실패 격리·500, report 필드, deadline 뒤 timeout 기록 |
| runtime | production API·maintenance에 `MEDIA_BUCKET` 없으면 시작 거부, local 기본 local 저장소, `exposesLocalRoutes`가 local 저장소에서만 참, `MEDIA_BUCKET` 설정 시 local route 404, worker·maintenance 역할에 MEDIA route 없음 |
| 전체 흐름 | `apiRoutes` + local 저장소 route를 직접 등록해 MEDIA-01 → PUT → MEDIA-02 → (fixture로 item에 연결) → ITEM-03에서 표시 URL로 GET 성공 → 연결 해제 → maintenance 정리 |
| 회귀 | `./gradlew test` 전체(B0~B5) |

## 11. 문서 갱신

`mvp-api-inventory.md`(MEDIA-01·02 상태와 확정 계약, OPS-01 media 정리), `mvp-api-implementation-order.md`(B6 상태와 미결정 해결 표), `mvp-product-api-design.md` 이미지 저장 절, `runtime-resources.md`(저장소 client·env), `wishlist-item-read-api.md`(`imageMediaId`·signed URL), `client/server-integration-status.md` MEDIA 행과 C8 클라이언트 계약(회전 적용·EXIF 제거·APP0·APP2 ICC·APP14 외 segment와 MPF 보조 이미지(HDR gain map 포함)가 없는 단일 JPEG, MEDIA-01 503은 같은 key로 재시도, 실제 byte 수와 회전 후 치수 선언, header 그대로 전송, mediaId cache key, 표시 URL 남은 유효시간 45~60분), INDEX, B6 구현 이력, 필요한 learning Q&A(signed URL·생성 전용 조건).

## 후속 묶음 인계

| 묶음 | 연결할 부분 |
| --- | --- |
| B7 | ITEM-04·07 연결은 owner → item → media `for update` 잠금(필수), 이전·새 media 두 행은 id 순서로 잠금, READY 확인, 연결한 media 행의 `updated_at` 갱신, 이전 media의 `released_at` 기록, `image_source = 'USER'`. ITEM-05 삭제 때 참조 해제·`released_at`·item `updated_at` 갱신(7절 방어 조건의 전제) |
| B10 | archive snapshot의 media 참조를 `MediaReferences`에 추가, 외부 URL snapshot 복사 |
| B11 | 실제 bucket·CORS 불필요 확인(앱 직접 PUT), signBlob 권한(`iam.serviceAccounts.signBlob`), URL 만료, 생성 전용 412, `x-goog-content-length-range` 강제 여부, signBlob 지연과 서명 executor 크기, 로그 누출 |
