# B6 사용자 이미지 업로드 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** media 테이블·storage adapter → MEDIA-01 → 직접 byte 업로드 → MEDIA-02 객체 확인 순서로 사용자 이미지 업로드를 만들고, 표시 URL과 OPS-01 media 정리를 연결한다.

**Architecture:** 기존 Ktor·JDBC modular monolith에 `app/media` 모듈을 추가한다. 저장소는 `MediaStorage` 인터페이스 뒤에 GCS·local·in-memory 구현을 둔다. DB transaction은 짧게 끊고, 서명·stat·read·delete는 transaction 밖에서 호출한다. JPEG 검증은 순수 함수로 분리해 DB 없이 테스트한다.

**Tech Stack:** Kotlin 2.3.21/JDK 17, Ktor 3.6.0, PostgreSQL 16, Flyway 11.20.0, Testcontainers 1.21.3, `com.google.cloud:google-cloud-storage:2.68.0`(이미 firebase-admin 경유로 runtime classpath에 있는 버전을 직접 선언), JDK ImageIO. 버전 변경 없음.

**Spec:** [B6 설계](../specs/2026-10-12-b6-media-upload-design.md). [제품·운영 결정](../../history/product-planning/mvp/decisions/b6-media-upload-policy-2026-10-12.md).

> 상태: ****계획 승인(2026-10-13)** · 구현 전**

## Global Constraints

- 서버·docs만 변경한다. client/·design/handoff/·다른 worktree는 수정하지 않는다. V1~V15·V17 SQL과 `V16__recoverable_read_indexes.kt`·`ReadIndexRollout.kt`는 수정하지 않는다. 새 migration은 V18이다.
- 형식·한도: `image/jpeg`만, 1~5,242,880 byte, 각 변 1~2048px, SOF0·SOF1·SOF2 중 정확히 하나, 8bit·component 1 또는 3, SOS 64개 이하, 허용 segment는 APP0·APP2(`ICC_PROFILE`)·APP14, 첫 EOI 뒤 byte 없음. 서명 executor는 thread 8·queue 64·대기 2초, 표시 서명은 single-flight. upload URL 15분(재전송으로 연장하지 않음), 읽기 URL 1시간·cache 재사용은 남은 45분 이상, 응답별 서명 병렬 8·총 2초, PENDING 동시 10개, 정리 유예 24시간, DELETING lease 2분, 정리 batch 50, read·검증 동시 4개, 저장소 RPC 5초.
- 서명을 빠뜨리면 이미지가 조용히 사라지므로, mapper의 이미지 조회표(`MediaUrls`)와 `apiRoutes`의 `media: MediaWiring`은 **기본값 없이** 받는다. 영향받는 기존 테스트는 해당 Task에서 명시적으로 고친다(`MediaUrls.NONE`이나 in-memory wiring을 넘김). `MaintenanceService(media = null)`(단계 생략)과 `MaintenanceReport` 새 필드의 기본값 0은 유지한다. 서명과 무관하기 때문이다. 기존 단계 순서와 timeout 목록 assertion은 Task 8에서 갱신한다.
- API 역할의 `module()`은 Firebase를 초기화하므로 테스트는 기존처럼 `routing { apiRoutes(…) }`를 직접 등록한다.
- 객체 경로 `media/{ownerId}/{mediaId}.jpg`. PUT 서명 header는 `Content-Type: image/jpeg`, `x-goog-if-generation-match: 0`, `x-goog-content-length-range: 1,5242880`.
- 잠금 순서: intent는 owner lock 하나, completion·정리는 media 행 하나만 잠근다. B7 연결은 owner → item → media(인계). 저장소 호출 중 DB connection을 쥐지 않는다. DB 시각은 `clock_timestamp()`.
- 정리는 행을 지우지 않고 `DELETED` tombstone으로 남겨 멱등 key를 계정 수명 동안 유지한다.
- 로그는 mediaId·단계 이름·예외 타입만. signed URL·query·예외 메시지를 남기지 않는다.
- 실제 bucket·signBlob 권한·실제 URL 만료는 B11이다. local은 실제 PostgreSQL과 in-memory/local 저장소로 검증한다. 실제 DB 테스트를 실행하지 못하면 해당 Task를 통과로 보지 않는다.
- 커밋은 `feature(server): 한글 설명`/`bugfix: …`/`docs: …` + 빈 줄 + 간결한 한글 본문, 이슈 번호 없음. 첫 커밋(이 계획·spec·결정 문서) 뒤 push하고, PR은 마지막 Task에서 develop 대상으로 연다.

## 파일 구조와 책임

경로의 `app/`는 `server/src/main/kotlin/app/`, 테스트는 `server/src/test/kotlin/app/` 기준이다(새 파일은 +).

| 파일 | 책임 |
| --- | --- |
| + `server/src/main/resources/db/migration/V18__media_assets.sql` | `media_assets`, `wishlist_items.media_asset_id`, FK·unique·index |
| `server/build.gradle.kts` | `google-cloud-storage:2.68.0` 직접 선언 |
| + `app/media/MediaModels.kt` | 상태, 한도 상수, `MediaAsset`, `MediaException`, 실패 사유 |
| + `app/media/JpegInspector.kt` | marker 구조·APP1·SOF 치수 검사(순수) |
| + `app/media/MediaImageValidator.kt` | 크기·Content-Type·inspector·ImageIO decode 순서 판정, 동시 4개 gate |
| + `app/media/MediaStorage.kt` | `MediaStorage`, `UploadTarget`, `StoredObject`, `SignedRead`, `MediaUrlSigner`(fun interface), `MediaStorageException` |
| + `app/media/MediaSigningExecutor.kt` | 공용 서명 executor(thread 8·queue 64·대기 2초), 표시 서명 single-flight |
| + `app/media/GcsMediaStorage.kt` | V4 signed PUT/GET, stat·read·delete(5초) |
| + `app/media/LocalMediaStorage.kt` | 파일 저장, HMAC 서명 URL, 생성 전용·만료 검사 |
| + `app/media/MediaRepository.kt` | insert·find·잠금 조회·상태 전이·PENDING count |
| + `app/media/MediaService.kt` | MEDIA-01 intent, MEDIA-02 completion |
| + `app/media/MediaUrlSigner.kt` | `CachingMediaUrlSigner`, `signAll`, `MediaUrls` |
| + `app/media/MediaReferences.kt` | 참조 판정 SQL 조각(B10 확장점) |
| + `app/media/MediaCleanup.kt` | OPS-01 media 단계 |
| + `app/http/MediaDtos.kt`, + `app/http/MediaRequestParser.kt`, + `app/http/MediaRoutes.kt` | DTO, 입력 파서, MEDIA route, `MediaWiring(service, signer)` |
| + `app/http/LocalMediaRoutes.kt` | local 저장소 PUT/GET |
| `app/wishlist/WishlistItemRowMapper.kt`, `app/wishlist/WishlistItem*.kt` | media join, `mediaId`·`mediaObjectKey` |
| `app/http/WishlistItemDtos.kt`, `WishlistItemViewMapper.kt`, `WishlistRoutes.kt`, `WishlistReadDtos.kt`(`ReadWindowViewMapper`), `WishlistReadRoutes.kt`, `HomeDtos.kt`(`HomeViewMapper`), `HomeRoutes.kt`, `PurposeDtos.kt`, `PurposeRoutes.kt` | `imageMediaId`, 서명 조회표 주입 |
| `app/purpose/PurposeRepository.kt`, `PurposeModels.kt` | 미리보기 media join |
| `app/maintenance/MaintenanceService.kt` | `media` 단계, report 필드 |
| `app/RuntimeConfig.kt`, `app/Main.kt` | `MEDIA_BUCKET`, 저장소 조립, local route 조건부 등록, `mediaStorage` 주입점 |
| + `testutil/InMemoryMediaStorage.kt`, + `testutil/MediaFixtures.kt` | fake 저장소(장애 주입), JPEG byte fixture 생성 |

## 테스트 실행과 RED/GREEN 기록

모든 Gradle 명령은 `server/`에서 [로컬 테스트 환경](../../architecture/server/local-test-environment.md#전체-테스트-실행)의 Podman/JDK 설정으로 실행한다.

```sh
b6_gradle() {
  env JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
    DOCKER_HOST=unix:///var/run/docker.sock \
    TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
    TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 RUN_REAL_URL_PILOT=0 ./gradlew "$@"
}
```

각 RED는 실패한 테스트 이름과 원인(assertion 실패 / 신규 symbol compile 실패)을 구분해 B6 구현 이력에 남긴다. GREEN은 실제 결과만 기록한다. 컨테이너 기동 실패 같은 환경 원인은 별도로 기록하고 통과로 덮어쓰지 않는다. DB fixture는 `withAnalysisDatabase`를 쓴다. JPEG fixture는 테스트 안에서 ImageIO로 생성하고, APP1·잘린 파일·CMYK·progressive 변형은 byte 조작이나 ImageIO metadata로 만든다. 저장소 binary fixture 파일은 추가하지 않는다.

## Review Focus

- 재전송이 upload URL 만료를 늘리지 않는지, 만료 직전 재전송이 남은 시간만큼만 서명된 URL을 받는지(Task 4).
- 두 completion이 동시에 검증을 끝낼 때 상태 반영이 한 번만 일어나고 둘 다 같은 응답을 받는지. 한쪽이 FAILED를 반영하고 객체를 지운 뒤 다른 쪽이 409·503이 아니라 저장된 422로 답하는지. FAILED 판정 뒤 객체 삭제 실패가 READY로 뒤집히지 않는지(Task 5).
- 정리와 B7 연결이 같은 media를 두고 겹칠 때 "DELETING인데 상품이 참조함" 상태가 생기지 않는지. 정리는 `for update skip locked`로 행만 잠근 뒤 **별도 문장**으로 참조를 확인해야 한다. 한 문장 안의 subquery는 잠금 대기 뒤 다시 평가되지 않는다. 이 구간은 latch로 재현하기 어려우므로 코드 구조와 리뷰로 확인하고, latch 테스트는 두 순서를 고정한다(Task 8).
- 허용 목록 밖 segment(APP1 Exif·XMP, APP2 MPF, APP13, COM)를 progressive scan 사이까지 막는지, EOI를 "파일 마지막 2 byte"가 아니라 순회로 처음 만난 EOI로 판정해 MPF trailing을 막는지. SOF·SOS 상한이 메모리·CPU 폭탄을 막는지. 잘린 entropy 데이터가 decode warning으로 잡히는지. iOS `jpegData`·Android `Bitmap.compress` 출력에 APP1·MPF 보조 이미지(HDR gain map)가 없는지 C8에서 확인할 항목으로 남긴다(Task 2).
- local 저장소 route가 production·`MEDIA_BUCKET` 설정에서 절대 등록되지 않는지. signed URL이 요청 로그에 남지 않는지(Task 6, 9).
- 목록 응답의 서명이 item 수만큼 원격 호출을 만들지 않는지(cache·병렬 8·총 2초), 서명 실패가 목록 전체를 실패시키지 않는지(Task 7).
- DELETING lease 안에서는 동시 maintenance가 같은 행을 다시 집지 않는지, lease가 끝나면 죽은 실행의 행을 이어받는지(Task 8).

---

### Task 1: V18 migration과 upgrade

**Files:** Create `server/src/main/resources/db/migration/V18__media_assets.sql`; modify `server/src/test/kotlin/app/DatabaseMigrationTest.kt`.

- [ ] RED: `all Flyway migrations apply to an empty postgres database`의 기대 버전 목록 끝에 `"18"`을 추가한다.
- [ ] RED: `V18 upgrade keeps V17 rows and adds nullable media reference` 추가. `target("17")`까지 migrate하고 item·job 1건씩 insert한 뒤 전체 migrate한다. 기존 컬럼 snapshot이 그대로이고 `media_asset_id`가 null인지 확인한다.
- [ ] RED: `V18 constraints protect media states and owner references` 추가. 다음이 모두 `PSQLException`이어야 한다. `content_type='image/png'`, `declared_width=2049`, `declared_byte_size=5242881`, `status='READY'`인데 `width` null, `status='FAILED'`인데 `failure_reason` null, 다른 owner의 media를 item에 연결, 같은 media를 두 item에 연결, 같은 `(owner, idempotency_key)` 두 번. 같은 owner의 READY media 연결은 성공한다.
- [ ] RED 실행: `b6_gradle test --tests 'app.DatabaseMigrationTest'`. 버전 목록 불일치·테이블 없음으로 실패해야 한다.
- [ ] 구현: spec 1절 SQL을 기존 migration 문체로 작성한다. transaction 안에서 일반 index를 쓰는 근거(운영 배포 B11 이전, 새 컬럼이 모두 null)를 구현 이력에 기록한다.
- [ ] GREEN: 같은 명령 + `--tests 'app.wishlist.ReadIndexRecoveryTest' --tests 'app.DatabaseMigrationRetryTest'`.
- [ ] 커밋: `feature(server): B6 media 테이블과 상품 참조 migration 추가`.

### Task 2: JPEG 검사와 이미지 검증

**Files:** Create `app/media/{MediaModels,JpegInspector,MediaImageValidator}.kt`, `testutil/MediaFixtures.kt`; test `media/{JpegInspectorTest,MediaImageValidatorTest}.kt`.

**Interfaces:**
```kotlin
enum class MediaFailureReason { SIZE_MISMATCH, CONTENT_TYPE_MISMATCH, NOT_JPEG, UNSUPPORTED_JPEG, METADATA_PRESENT, DIMENSIONS_EXCEEDED, DIMENSIONS_MISMATCH, DECODE_FAILED }
sealed interface JpegInspection {
    data class Parsed(val width: Int, val height: Int) : JpegInspection
    data class Rejected(val reason: MediaFailureReason) : JpegInspection   // NOT_JPEG·UNSUPPORTED_JPEG·METADATA_PRESENT
}
object JpegInspector { fun inspect(bytes: ByteArray): JpegInspection }
data class DeclaredImage(val byteSize: Int, val width: Int, val height: Int)
sealed interface ImageVerdict { data class Valid(val byteSize: Int, val width: Int, val height: Int) : ImageVerdict
                                data class Invalid(val reason: MediaFailureReason) : ImageVerdict }
class MediaImageValidator(maxConcurrent: Int = 4) {
    fun precheck(stored: StoredObject, declared: DeclaredImage): MediaFailureReason?  // byte를 읽기 전
    fun verify(bytes: ByteArray, declared: DeclaredImage): ImageVerdict
    fun <T> gated(block: () -> T): T   // 포화 시 MediaException("MEDIA_BUSY")
}
```

- [ ] RED: `MediaFixtures.jpeg(width, height)`(ImageIO 생성, APP0만), `grayscale(w,h)`, `withApp1(bytes, "Exif")`, `withApp1(bytes, "XMP")`, `progressive(w,h)`, `progressiveWithApp1BetweenScans()`, `withTrailing(bytes)`, `withoutEoi(bytes)`, `truncatedAfterSos(bytes)`, `withSof(bytes, precision, components)`(SOF byte 조작으로 4 component·12bit), `withSofMarker(bytes, 0xC3)`(SOF3·SOF9), `withDuplicateSof`, `withSegment(bytes, marker, payload)`(APP2 ICC_PROFILE·APP2 MPF·APP13·APP14·COM), `withFillBytes`, `withTem`, `progressiveWithScans(n)`(SOS 65개), `withMpfTrailer`(EOI 뒤 FFD8…FFD9 보조 이미지), `png(w,h)`를 만든다.
- [ ] RED: `JpegInspectorTest`. 정상 2048×1536·1×1·grayscale·progressive(SOF2)·APP2 ICC_PROFILE·APP14·fill byte·TEM·RSTn → `Parsed` 치수. SOI 없음·PNG·EOI 없음·marker 길이가 파일 밖·SOF 없음 → NOT_JPEG. SOF3·SOF9·SOF 2개·12bit·4 component·SOS 65개 → UNSUPPORTED_JPEG. APP1 Exif·XMP·APP2 MPF·APP13·COM·scan 사이 APP1·MPF trailer(파일이 FFD9로 끝나도) → METADATA_PRESENT.
- [ ] RED: `MediaImageValidatorTest`. spec 5절 표 순서대로 사유 하나씩(크기 불일치, 5MiB 초과는 read 전에 판정, Content-Type, NOT_JPEG, UNSUPPORTED_JPEG, METADATA_PRESENT, 2049px, 선언 치수 불일치, SOS 뒤 잘린 데이터 DECODE_FAILED(warning)). 정상·progressive·grayscale은 `Valid`. 동시 5번째 `gated`는 `MEDIA_BUSY`.
- [ ] RED 실행: `b6_gradle test --tests 'app.media.*'`. 신규 symbol compile 실패여야 한다.
- [ ] 구현: inspector는 SOI부터 순회로 처음 만난 EOI까지 모든 marker를 본다. marker 앞 `FF` fill byte는 건너뛰고, TEM·RSTn은 2 byte만 소비하며, 길이가 있는 segment는 길이로 건너뛴다. SOS 뒤 entropy 구간은 `FF00`·RSTn을 데이터로 보고 그 밖의 `FFxx`를 다음 marker로 본다. SOF0·SOF1·SOF2만 받아 precision·치수·component 수를 읽고, 그 밖의 SOFn(DHT·JPG·DAC 제외)은 UNSUPPORTED_JPEG. APP0·APP2(`ICC_PROFILE\0` 식별자)·APP14 외 APPn·COM은 METADATA_PRESENT. 첫 EOI 뒤 byte가 있으면 METADATA_PRESENT. decode는 `ImageIO.getImageReadersByFormatName("jpeg")` reader에 `addIIOReadWarningListener`를 달고 치수 확인 뒤 `read(0)`, warning이 있으면 DECODE_FAILED. `ImageIO.setUseCache(false)`. gate는 `Semaphore.tryAcquire()`이며 read와 verify만 감싼다.
- [ ] GREEN: 같은 명령.
- [ ] 커밋: `feature(server): B6 JPEG 구조·치수·메타데이터 검증 추가`.

### Task 3: 저장소 adapter

**Files:** Create `app/media/{MediaStorage,GcsMediaStorage,LocalMediaStorage}.kt`, `testutil/InMemoryMediaStorage.kt`; modify `server/build.gradle.kts`; test `media/{GcsMediaStorageTest,LocalMediaStorageTest}.kt`.

**Interfaces:** spec 2절 `MediaStorage`, `SignedRead`, `fun interface MediaUrlSigner`(Task 4~6이 쓰므로 여기서 만든다). `MediaStorageException`(장애), `MediaTooLarge`. `class MediaSigningExecutor(threads = 8, queue = 64, wait = 2s) : AutoCloseable { fun <T> await(block: () -> T): T?; fun <T> singleFlight(key: String, block: () -> T, onLate: (T) -> Unit): T? }`(포화·시간 초과는 null). `LocalMediaStorage(root: Path, baseUrl: String, secret: ByteArray = random, clock)` + `acceptUpload(objectKey, query, headers, body: InputStream): LocalUploadResult`, `openForRead(objectKey, query): Path?`. `InMemoryMediaStorage`는 객체 map, 실패 주입(`failSign`, `failStat`, `failDelete`), 호출 횟수 기록.

- [ ] RED: `MediaSigningExecutorTest`. 3초 걸리는 block → 2초에 null, 끝난 뒤 `onLate` 호출. 같은 key 동시 5개 → block 1회 실행·모두 같은 결과. thread 8·queue 64가 차면 즉시 null. close 뒤 거부.
- [ ] RED: `GcsMediaStorageTest`. 테스트 안에서 RSA key로 만든 `ServiceAccountCredentials`와 `StorageOptions.newBuilder().setProjectId("test")`로 offline 서명한다. PUT URL query의 `X-Goog-Algorithm=GOOG4-RSA-SHA256`, `X-Goog-Expires`가 남은 초(≤900), `X-Goog-SignedHeaders=content-type;host;x-goog-content-length-range;x-goog-if-generation-match`, 경로 `media/{owner}/{id}.jpg`. GET URL `X-Goog-Expires=3600`. 반환 `headers`가 세 header와 정확히 같음.
- [ ] RED: `LocalMediaStorageTest`. 정상 PUT 후 stat·read, 서명 변조·만료·Content-Type·`x-goog-if-generation-match`·`x-goog-content-length-range` 누락이나 값 변경 → 403, 두 번째 PUT 412, 동시 PUT 2개(latch) 중 하나만 성공·파일 byte 온전, 쓰는 중에는 stat이 null, 5MiB+1 → 413, read 서명 검증, delete 없는 객체도 성공, objectKey의 `..` 거부.
- [ ] RED 실행: `b6_gradle test --tests 'app.media.*Storage*' --tests 'app.media.MediaSigningExecutorTest'`.
- [ ] 구현: `implementation("com.google.cloud:google-cloud-storage:2.68.0")`. `./gradlew dependencies`로 resolved 버전이 그대로 2.68.0인지 확인해 이력에 남긴다. GCS client는 `StorageOptions.setRetrySettings`(maxAttempts 1, totalTimeout 5초)와 `HttpTransportOptions`(connect·read timeout 5초)로 만든다. read는 `reader()`로 `maxBytes + 1`까지만 읽는다. local 쓰기는 임시 파일 → `Files.move(ATOMIC_MOVE)`, 대상이 있으면 412. `NotFound`는 null/성공, 그 밖의 예외는 `MediaStorageException`.
- [ ] GREEN: 같은 명령.
- [ ] 커밋: `feature(server): B6 GCS·local 저장소 adapter 추가`.

### Task 4: MEDIA-01 intent 서비스

**Files:** Create `app/media/{MediaRepository,MediaService}.kt`; test `media/MediaIntentServiceTest.kt`.

**Interfaces:**
```kotlin
data class IntentRequest(val contentType: String, val byteSize: Int, val width: Int, val height: Int)
data class MediaView(val asset: MediaAsset, val upload: UploadTarget?, val display: SignedRead?)
data class MediaIntentResult(val view: MediaView, val replayed: Boolean)
class MediaService(source: DataSource, storage: MediaStorage, signer: MediaUrlSigner, validator: MediaImageValidator,
                   signing: MediaSigningExecutor, beforeApply: (UUID) -> Unit = {}) {   // beforeApply: 검증 뒤·반영 전 test hook
    fun createIntent(owner: UUID, key: UUID, request: IntentRequest): MediaIntentResult
    fun complete(owner: UUID, id: UUID): MediaView   // Task 5
}
```
`MediaException(code, reason: MediaFailureReason? = null, retryAfterSeconds: Int? = null)`.

- [ ] RED(PostgreSQL): 새 intent → PENDING 행, `upload_expires_at`이 DB 시각 + 15분, object key 형식, 저장소 `uploadTarget` 호출 1회. 같은 key·같은 요청 → `replayed`, 같은 id, 만료 시각 불변, 새 서명 호출. 같은 key·다른 byteSize → `IDEMPOTENCY_KEY_REUSED`. 만료로 옮긴 PENDING(`update … set upload_expires_at = clock_timestamp() - interval '1 second'`) 재전송 → `MEDIA_UPLOAD_EXPIRED`. READY 재전송 → upload null·display 있음. FAILED 재전송 → `MEDIA_INVALID_IMAGE`+사유. DELETED 재전송 → `MEDIA_UPLOAD_EXPIRED`.
- [ ] RED: 만료 전 PENDING 10개 → 11번째 `MEDIA_UPLOAD_LIMIT_REACHED`, `retryAfterSeconds`가 가장 이른 만료까지(1 이상). 만료된 PENDING·READY는 세지 않음. 다른 owner와 분리.
- [ ] RED: `failSign` 주입 → `MEDIA_STORAGE_UNAVAILABLE`, 행은 남음. 장애 해제 후 같은 key → replay 성공. 3초 걸리는 upload 서명 → 2초에 `MEDIA_STORAGE_UNAVAILABLE`.
- [ ] RED(경합): 같은 key 동시 2요청(latch) → 행 1개, 하나는 replay.
- [ ] RED 실행: `b6_gradle test --tests 'app.media.MediaIntentServiceTest'`.
- [ ] 구현: spec 3절 순서. upload 서명은 `signing.await { storage.uploadTarget(…) }`. fingerprint는 `MutationReceipts.fingerprint`(canonical JSON SHA-256) 재사용. 서명 만료는 `upload_expires_at`(DB 값)을 그대로 넘기고, 저장소는 `expiresAt - now`(최소 1초)로 서명한다.
- [ ] GREEN: 같은 명령.
- [ ] 커밋: `feature(server): B6 MEDIA-01 업로드 intent 서비스 구현`.

### Task 5: MEDIA-02 completion 서비스

**Files:** modify `app/media/{MediaService,MediaRepository}.kt`; test `media/MediaCompletionServiceTest.kt`.

- [ ] RED(PostgreSQL): 정상 JPEG 업로드 → READY, `byte_size`·`width`·`height`·`ready_at`, display 서명. READY 재전송 → 같은 결과, 저장소 read 추가 호출 없음. 다른 owner → `MEDIA_NOT_FOUND`. 객체 없음 → `MEDIA_OBJECT_MISSING`, PENDING 유지. `failStat` → `MEDIA_STORAGE_UNAVAILABLE`, PENDING 유지. 만료 → `MEDIA_UPLOAD_EXPIRED`.
- [ ] RED: 사유별 FAILED(Task 2 fixture 재사용) → 행 FAILED·`failure_reason`, 객체 삭제 호출. FAILED 재전송 → 같은 422 사유, 검증 재실행 없음. `failDelete` 주입 시에도 FAILED 유지.
- [ ] RED(경합): 같은 media completion 2개를 `beforeApply` hook의 latch로 검증 직후에 맞춤 → READY 전이 1회(`updated_at` 1회 변경), 두 응답 모두 READY.
- [ ] RED: display 서명 실패 → READY 반영, display null.
- [ ] RED(경합): A가 FAILED 반영·객체 삭제를 마친 뒤 B의 stat·read가 객체 없음 → B는 행을 다시 읽어 같은 422로 응답.
- [ ] RED: 재업로드. 만료 410 뒤 새 key → READY, FAILED 뒤 새 key → READY, FAILED는 PENDING 한도에서 제외. DELETING·DELETED completion → 410.
- [ ] RED 실행: `b6_gradle test --tests 'app.media.MediaCompletionServiceTest'`.
- [ ] 구현: spec 5절 1~6단계. `stat` → `precheck`는 gate 밖, `validator.gated { read(objectKey, 5MiB) → verify }`. 객체 없음·저장소 오류로 끝나기 전에 행을 다시 읽는다. 반영은 `select … for update` 후 `status='PENDING'`일 때만 update.
- [ ] GREEN: 같은 명령.
- [ ] 커밋: `feature(server): B6 MEDIA-02 업로드 완료 확인 구현`.

### Task 6: MEDIA route와 local 저장소 route

**Files:** Create `app/http/{MediaDtos,MediaRequestParser,MediaRoutes,LocalMediaRoutes}.kt`; modify `app/Main.kt`(`apiRoutes`에 필수 `media: MediaWiring` 인자, `mediaRoutes` 등록), `AnalysisRuntimeFlowTest.kt`·`http/ApiRouteExposureTest.kt`(in-memory 저장소 wiring 전달); test `http/{MediaRequestParserTest,MediaRoutesTest,LocalMediaRoutesTest}.kt`.

**Interfaces:** `data class MediaWiring(val service: MediaService, val signer: MediaUrlSigner)`, `fun Route.mediaRoutes(wiring: MediaWiring, resolve: suspend (ApplicationCall) -> UUID?)`, `fun Route.localMediaRoutes(storage: LocalMediaStorage)`. Task 7에서 item·목적·홈 route가 `wiring.signer`를 받는다.

- [ ] RED: 파서. 필드 누락·알 수 없는 필드·문자열 숫자·소수·0 이하 → `INVALID_MEDIA_REQUEST`+fields, body `contentType`이 `image/png` → `UNSUPPORTED_MEDIA_TYPE`, 5MiB+1 → `MEDIA_TOO_LARGE`, 2049 → `MEDIA_DIMENSIONS_EXCEEDED`. 동시 위반(png + 5MiB+1 + 2049) → `UNSUPPORTED_MEDIA_TYPE`, (5MiB+1 + 2049) → `MEDIA_TOO_LARGE`.
- [ ] RED: `MediaRoutesTest`(Test-Owner header 패턴). 401, key 없음 400, 201(`Location` 없음) + 응답 key 집합(`media`·`upload`·`display`·`limits`), replay 200 + `Idempotency-Replayed`, 409·410·422·429(`Retry-After`)·503 매핑, completion 400(잘못된 id)·404·409·410·422(`details.reason`)·200, 다른 owner 404.
- [ ] RED: `LocalMediaRoutesTest`. 받은 `upload.url`·`headers`로 PUT 200 → completion READY → `display.url` GET 200·같은 byte, header 누락 403, 덮어쓰기 412, 413, 만료 403.
- [ ] RED 실행: `b6_gradle test --tests 'app.http.Media*' --tests 'app.http.LocalMediaRoutesTest'`.
- [ ] 구현: 기존 `categoryRoutes`처럼 `withContext(Dispatchers.IO)`에서 서비스 호출, 오류는 spec 9절 표대로. local PUT body는 `receiveChannel()`에서 5MiB+1까지만 읽는다.
- [ ] GREEN: 같은 명령.
- [ ] 커밋: `feature(server): B6 MEDIA route와 local 업로드 경로 추가`.

### Task 7: 표시 URL

**Files:** Create `app/media/MediaUrlSigner.kt`(`CachingMediaUrlSigner`·`signAll`·`MediaUrls`); modify `app/wishlist/{WishlistItemRowMapper,WishlistItem…}.kt`, `app/purpose/{PurposeRepository,PurposeModels}.kt`, `app/http/{WishlistItemDtos,WishlistItemViewMapper,WishlistRoutes,WishlistReadDtos,WishlistReadRoutes,HomeDtos,HomeRoutes,PurposeDtos,PurposeRoutes}.kt`, `app/Main.kt`(`apiRoutes`의 `media` 인자); test `media/CachingMediaUrlSignerTest.kt`, 기존 `http/WishlistRoutesTest`·`WishlistReadRoutesTest`·`HomeRoutesTest`·`PurposeRoutesTest`에 사례 추가. mapper 인자에 기본값이 없으므로 직접 부르는 기존 테스트 `analysis/MetadataPersistenceTest`, `category/CategoryAiIntegrationTest`, `http/WishlistItemViewMapperTest`, `http/ReadCursorHashReuseTest`, `wishlist/{WishlistEmptyPageTest,WishlistReadPolicyParityTest,WishlistReadRepositoryTest,WishlistReadServiceTest}`에 `MediaUrls.NONE`을 명시적으로 넘긴다. `apiRoutes`는 Task 6에서 이미 wiring을 받는다.

**Interfaces:**
```kotlin
fun MediaUrlSigner.signAll(keys: Set<String>, parallelism: Int = 8, budget: Duration = 2s): MediaUrls  // 상한 초과 key는 빠짐
class CachingMediaUrlSigner(storage: MediaStorage, signing: MediaSigningExecutor, clock: Clock, validFor: Duration = 1h, reuseWhile: Duration = 45m, maxEntries: Int = 10_000) : MediaUrlSigner
class MediaUrls(private val byKey: Map<String, SignedRead>) { fun url(objectKey: String?): String?; companion object { val NONE: MediaUrls } }
```
`WishlistItem`에 `mediaId: UUID?`, `mediaObjectKey: String?`, `PurposePreview`에 같은 두 필드. `ProductDto.imageMediaId: String?`, `PurposePreviewDto.imageMediaId: String?`.

- [ ] RED: signer cache. 같은 key 두 번 → 저장소 호출 1회, 15분 1초 경과(남은 44분 59초) → 재서명, 저장소 예외 → null·cache 안 함, 10,001번째 → 가장 이른 만료 제거. `signAll`에서 1초씩 걸리는 signer와 key 40개 → 2초 안팎에 반환하고 끝나지 않은 key는 빠짐, 동시 호출 8개 이하, 늦게 끝난 key는 다음 `signAll`에서 cache hit. 같은 key를 동시에 두 응답이 요청 → 저장소 서명 1회.
- [ ] RED(PostgreSQL): fixture로 READY media를 item에 연결(`update wishlist_items set media_asset_id=…`). ITEM-03·ITEM-02·HOME-02·HOME-01 actionGroups 카드의 `imageUrl`이 서명 URL, `imageMediaId`가 id. 연결 없는 item은 기존 `product_image_url`과 null. media가 DELETING이면 연결돼 있어도 `product_image_url` fallback. PUR-01 SUMMARY·HOME-01 recentPurposes 미리보기도 같음. 서명 실패 → 해당 이미지만 `imageUrl` null, 응답 200.
- [ ] RED: ITEM-01 재전송. 생성 뒤 fixture로 READY media 연결 → 같은 key로 ITEM-01 재전송 → 200 응답의 `imageUrl`이 서명 URL, `imageMediaId`가 id. 서명 실패 시 `imageUrl` null·`imageMediaId` 유지·외부 `product_image_url`로 대신하지 않음.
- [ ] RED 실행: 대상 테스트.
- [ ] 구현: mapper는 `map(item, urls: MediaUrls)`(기본값 없음). ITEM-01 생성·재전송, ITEM-03, ITEM-02, HOME-01·02, PUR-01 route가 서비스 결과에서 object key를 모아 같은 IO 블록에서 `signAll` 한 뒤 mapper에 넘긴다. 서명은 transaction 밖이다.
- [ ] GREEN: 같은 명령 + `b6_gradle test --tests 'app.wishlist.*' --tests 'app.home.*' --tests 'app.purpose.*'`.
- [ ] 커밋: `feature(server): B6 상품·목적 응답에 사용자 이미지 표시 URL 연결`.

### Task 8: OPS-01 media 정리

**Files:** Create `app/media/{MediaReferences,MediaCleanup}.kt`; modify `app/maintenance/MaintenanceService.kt`; test `media/MediaCleanupTest.kt`, `maintenance/MaintenanceServiceTest.kt`.

**Interfaces:** `data class MediaCleanupReport(val deleted: Int, val deleteFailed: Int)`, `class MediaCleanup(source, storage) { fun run(deadline: Long): MediaCleanupReport }`. `MaintenanceService` 생성자에 `media: ((Long) -> MediaCleanupReport)? = null` 추가, report에 `mediaDeleted = 0`, `mediaDeleteFailed = 0`(기본값).

- [ ] RED(PostgreSQL): 시각은 fixture UPDATE로 옮긴다. 만료 PENDING 23h59m 보존·24h 삭제, FAILED 같은 경계, 미참조 READY `ready_at` 기준·`released_at` 기준, ACTIVE·ARCHIVED item 참조 보존, DELETED lifecycle item 참조는 item `updated_at` 24시간 뒤 삭제(그 전엔 보존), 삭제 뒤 tombstone(`DELETED`·`deleted_at`)과 저장소 delete 호출.
- [ ] RED: `failDelete` → DELETING 유지·`deleteFailed` 1, lease 2분 안의 다음 실행은 건너뜀, lease를 지난 실행에서 DELETED. batch 50 초과 시 다음 실행이 나머지 처리. deadline 경과 시 남은 후보를 건드리지 않음.
- [ ] RED(경합): 동시 cleanup 2개 → delete 호출 1회. 정리와 연결 fixture(owner → item → media `for update` 후 `status='READY'`면 연결하고 media `updated_at` 갱신) 경합을 latch로 두 순서 모두(연결이 media를 잠근 채 대기하는 동안 정리가 잠금 시도 → skip, 정리가 잠근 뒤 연결 대기 → 연결 거절) → 최종 상태가 (READY + 참조) 또는 (DELETED + 미참조) 중 하나.
- [ ] RED: `MaintenanceServiceTest`. media 단계 예외 → `failedSteps`에 `media`, 다른 단계 정상, deadline 뒤 `timeout:media`. 기존 단계 순서 assertion `[dispatch, reconcile, pending, budget]`에 `media`를, timeout 목록에 `timeout:media`를 추가하고, `media = null`이면 단계를 생략하는 사례를 둔다. `MaintenanceRoutesTest`·`AnalysisRuntimeFlowTest`는 기본값으로 compile이 유지되는지 확인한다.
- [ ] RED 실행: `b6_gradle test --tests 'app.media.MediaCleanupTest' --tests 'app.maintenance.*'`.
- [ ] 구현: spec 7절. 후보 발견 SQL은 `MediaReferences.unreferenced("m")` 조각을 쓴다. DELETING 재시도는 `updated_at <= now - 2분`. 후보 처리는 `select … where id=? for update skip locked`(조건 없이 행만 잠금) → **별도 문장**으로 상태·시각·참조 재확인 → DELETING update 순서로 하고, 한 문장으로 합치지 말라는 이유를 코드 주석으로 남긴다.
- [ ] GREEN: 같은 명령 + `--tests 'app.http.MaintenanceRoutesTest'`.
- [ ] 커밋: `feature(server): B6 maintenance에 미참조 media 정리 추가`.

### Task 9: runtime 조립과 전체 흐름

**Files:** modify `app/RuntimeConfig.kt`, `app/Main.kt`; test `RuntimeConfigTest.kt`, `http/ApiRouteExposureTest.kt`, + `MediaUploadFlowTest.kt`.

- [ ] RED: `RuntimeConfigTest`. 기존 production API·maintenance 성공 fixture(`production api cannot start without credentials`, `maintenance requires the database and production queue settings` 등)에 `MEDIA_BUCKET`을 추가하고, 없으면 시작 거부하는 사례를 더한다. local은 없어도 통과하고 `MediaStorageConfig.Local`(기본 dir·`http://localhost:{PORT}`). `MEDIA_BUCKET`이 있으면 `Gcs`. `exposesLocalRoutes`는 Local에서만 참. worker 역할은 media env를 요구하지 않음.
- [ ] RED: `ApiRouteExposureTest`(기존처럼 `routing { apiRoutes(…) }`). `media` 인자가 있으면 MEDIA route 존재, local route는 `exposesLocalRoutes`가 참일 때만 등록, Gcs 설정에서 `/local/media-objects/**` 404. worker·maintenance route 함수에 MEDIA route 없음.
- [ ] RED: `MediaUploadFlowTest`(PostgreSQL + `LocalMediaStorage` 임시 디렉터리). `routing { apiRoutes(source, …, media = MediaWiring(service, signer)) { owner }; localMediaRoutes(local) }`로 띄우고 MEDIA-01 → PUT → MEDIA-02 → fixture로 item 연결 → ITEM-03 `imageUrl` GET이 같은 byte. 그 뒤 연결 해제(`media_asset_id=null`, `released_at`을 25시간 전으로) → `MaintenanceService`(media 단계 포함) 실행 → 객체 삭제와 tombstone.
- [ ] RED 실행: 대상 테스트.
- [ ] 구현: `RuntimeConfig`에 `MediaStorageConfig`(`Gcs(bucket)` 또는 `Local(dir, baseUrl)`)와 `exposesLocalRoutes`. `Main`은 API·maintenance에서 저장소를 만들고 `RuntimeResources`에 등록한다. `module(..., mediaStorage: MediaStorage? = null)`.
- [ ] GREEN: 같은 명령.
- [ ] 커밋: `feature(server): B6 media 저장소 runtime 조립`.

### Task 10: 문서·독립 리뷰·전체 회귀·PR

- [ ] 전체 회귀 `b6_gradle test`. 통과·skip 수를 기록한다.
- [ ] 독립 리뷰(서브에이전트)를 받아 Critical·Important를 반영하고 재실행한다.
- [ ] 문서: spec 11절 목록, `mvp-api-inventory.md` MEDIA-01·02 **구현(B6)**, OPS-01 media 정리, `mvp-api-implementation-order.md` B6 체크·미결정 표 해결, `client/server-integration-status.md` MEDIA 행·C8 계약, INDEX들, `docs/history/architecture/server/b6-media-upload-implementation-2026-10-12.md`(범위·RED/GREEN·검증·B7/B10/B11 인계), learning Q&A.
- [ ] 커밋: `docs: B6 사용자 이미지 업로드 구현 기록`.
- [ ] push 후 develop 대상 PR을 연다.

## 자체 검토

- spec 10절의 모든 검증 영역이 Task 1~9의 RED에 대응한다. upgrade(1), JPEG(2), adapter(3), MEDIA-01(4·6), MEDIA-02(5·6), 표시(7), 정리·경합·maintenance(8), runtime·전체 흐름(9), 회귀(10).
- B7(연결 API), B10(archive 참조), B11(실제 권한)의 코드는 만들지 않는다. 경합 테스트의 연결 SQL은 테스트 fixture로만 둔다.
