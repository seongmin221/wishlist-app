# 사용자 이미지 업로드 API 계약

> 확정 계약 · MEDIA-01·02 · 표시 URL · OPS-01 media 정리 · 2026-10-13

## 적용 범위

Firebase 인증 owner가 사진 보관함에서 고른 사진을 private 저장소에 직접 올리고, 검증된 READY `mediaId`를 받는 계약이다. 상품에 연결하는 ITEM-04·07은 B7, archive snapshot 참조는 B10이다. 실제 bucket·signBlob 권한·URL 만료는 B11에서 확인한다. 결정은 [B6 제품·운영 결정](../../history/product-planning/mvp/decisions/b6-media-upload-policy-2026-10-12.md), 설계 근거는 [B6 spec](../../superpowers/specs/2026-10-12-b6-media-upload-design.md)에 있다.

## 흐름

```text
앱: 회전 적용·EXIF 제거·긴 변 2048px JPEG 재인코딩
  → MEDIA-01 POST /v1/media/upload-intents   (mediaId + 15분 signed PUT)
  → PUT upload.url (받은 headers 그대로)
  → MEDIA-02 POST /v1/media/{id}/completion  (READY + 표시 URL)
  → (B7) ITEM-04/07에 mediaId 연결
```

## 한도

| 항목 | 값 |
| --- | --- |
| 형식 | `image/jpeg`만. SOF0·SOF1·SOF2 중 정확히 하나, 8bit, component 1 또는 3, SOS 64개 이하 |
| 크기·치수 | 1~5,242,880 byte, 각 변 1~2048px |
| 허용 segment | APP0(JFIF), APP2(`ICC_PROFILE`), APP14(Adobe). 그 밖의 APPn·COM, 첫 EOI 뒤 byte는 거절 |
| upload URL | 15분. 재전송으로 연장하지 않음 |
| 표시 URL | 1시간 발급, 남은 시간 45분 이상이면 재사용 → 응답 URL의 남은 유효시간은 45~60분 |
| 동시 PENDING | owner당 만료 전 10개 |

## MEDIA-01 업로드 intent

`POST /v1/media/upload-intents`, 필수 정규 UUID `Idempotency-Key`.

```json
{ "contentType": "image/jpeg", "byteSize": 812345, "width": 2048, "height": 1536 }
```

`byteSize`는 인코딩한 실제 byte 수, `width`·`height`는 회전을 적용한 뒤의 치수다. completion이 실제 객체와 정확히 비교한다. 알 수 없는 필드는 `INVALID_MEDIA_REQUEST`이고 `details.fields`에 그 이름을 넣는다. 정수는 18자리까지 받으며, 5MiB를 넘는 큰 값은 `MEDIA_TOO_LARGE`다.

**응답 201**(새 intent) / **200 + `Idempotency-Replayed: true`**(재전송). `Location`은 없다.

```json
{
  "media": { "id": "…", "status": "PENDING", "contentType": "image/jpeg",
             "byteSize": 812345, "width": 2048, "height": 1536, "expiresAt": "2026-10-13T01:15:00Z" },
  "upload": { "method": "PUT", "url": "https://storage.googleapis.com/…",
              "headers": { "Content-Type": "image/jpeg", "x-goog-if-generation-match": "0",
                           "x-goog-content-length-range": "1,5242880" },
              "expiresAt": "2026-10-13T01:15:00Z" },
  "display": null,
  "limits": { "contentTypes": ["image/jpeg"], "maxBytes": 5242880, "maxLongEdge": 2048 }
}
```

| 같은 key 재전송 시 행 상태 | 응답 |
| --- | --- |
| PENDING, 만료 전 | 200, 같은 mediaId, 새로 서명한 URL, 처음 만료 시각 |
| PENDING 만료 후, DELETING, DELETED | 410 `MEDIA_UPLOAD_EXPIRED` |
| READY | 200, `upload: null`, `display` 포함 |
| FAILED | 422 `MEDIA_INVALID_IMAGE` + `details.reason` |

## MEDIA-02 업로드 완료 확인

`POST /v1/media/{id}/completion`, body 없음. 업로드 만료는 결과를 반영하는 순간의 DB 시각으로 판정한다. 검증하는 사이 기한이 지나면 410이다. 응답은 MEDIA-01과 같은 모양이며 READY에서는 `media.expiresAt`·`upload`가 null이고 `display`가 있다. 검증값을 반환한다. 같은 media 재호출은 같은 결과다. 표시 URL 서명이 실패하면 `display`만 null이다. completion은 상품 정보를 바꾸지 않는다.

`details.reason` 값: `SIZE_MISMATCH`, `CONTENT_TYPE_MISMATCH`, `NOT_JPEG`, `UNSUPPORTED_JPEG`, `METADATA_PRESENT`, `DIMENSIONS_EXCEEDED`, `DIMENSIONS_MISMATCH`, `DECODE_FAILED`. FAILED가 되면 객체를 지우고 PENDING 한도에서 빠진다.

## 오류

| status | code | 앱 동작 |
| --- | --- | --- |
| 400 | `INVALID_IDEMPOTENCY_KEY`, `INVALID_MEDIA_ID` | 요청 수정 |
| 401 | `UNAUTHORIZED` | 재로그인 |
| 404 | `MEDIA_NOT_FOUND` | 없음·다른 owner |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 새 key |
| 409 | `MEDIA_OBJECT_MISSING` | PUT 뒤 다시 completion |
| 410 | `MEDIA_UPLOAD_EXPIRED` | 새 key로 intent부터 |
| 422 | `INVALID_MEDIA_REQUEST`(+`details.fields`) → `UNSUPPORTED_MEDIA_TYPE` → `MEDIA_TOO_LARGE` → `MEDIA_DIMENSIONS_EXCEEDED` | 동시 위반이면 왼쪽 순서의 첫 코드 하나 |
| 422 | `MEDIA_INVALID_IMAGE` + `details.reason` | 다시 인코딩해 새 key로 |
| 429 | `MEDIA_UPLOAD_LIMIT_REACHED` + `Retry-After` | 기다렸다 재시도 |
| 503 | `MEDIA_STORAGE_UNAVAILABLE` | **같은 key**로 재시도(새 key는 PENDING을 하나 더 차지) |
| 503 | `MEDIA_BUSY` + `Retry-After: 1` | 잠시 뒤 completion 재시도 |

## 표시 URL

ITEM-01 생성·재전송, ITEM-02, ITEM-03, HOME-01 `actionGroups[].previews`·`recentPurposes[].previews`, HOME-02, PUR-01 SUMMARY `previews`가 같은 규칙을 쓴다.

- 상품에 READY media가 연결돼 있으면 `product.imageUrl`(미리보기는 `imageUrl`)은 signed GET URL이고 `imageMediaId`는 media id다. 클라이언트는 매 응답 바뀌는 URL 대신 `imageMediaId`를 이미지 cache key로 쓴다.
- 서명에 실패하거나 응답별 2초 budget을 넘으면 `imageUrl`만 null이고 `imageMediaId`는 그대로다. 외부 이미지 URL로 대신하지 않는다. placeholder를 보여 주고 다음 조회에서 다시 얻는다.
- 응답 하나는 cache에 없는 이미지를 최대 32개까지 새로 서명한다. 그래서 cache가 빈 상태의 큰 목록(ITEM-02 `limit=100`)에서는 일부 이미지가 첫 응답에서 `imageUrl: null`일 수 있다. 표시 서명 executor(thread 8 + queue 64)와 2초 budget도 같은 결과를 낼 수 있다. 업로드 서명은 별도 executor(thread 2 + queue 16)를 써서 목록 부하에 막히지 않는다. 늦게 끝난 서명은 cache에 들어가 다음 조회에서 채워진다. 실제 signBlob 지연을 보고 B11에서 executor 크기를 다시 정한다.
- 연결된 media가 없으면 기존처럼 분석으로 얻은 외부 URL이고 `imageMediaId`는 null이다. 외부 URL은 서명·복사하지 않는다.

## local 개발

`APP_ENV=local`이고 `MEDIA_BUCKET`이 없으면 API가 `MEDIA_LOCAL_DIR`(기본 `build/local-media`)에 저장하고 `/local/media-objects/**` PUT·GET을 연다. URL은 `MEDIA_LOCAL_BASE_URL`(기본 `http://localhost:{PORT}`) 기준이며 Android 에뮬레이터처럼 다른 주소로 접근하면 지정한다. 서명 위조·만료·header 누락 403, 빈 body 400, 이미 있는 객체 412, 5MiB를 넘는 body 413, 서명 뒤 지워진 객체 GET 404, 파일 시스템 오류 503이다. production이나 `MEDIA_BUCKET` 설정에서는 이 경로를 등록하지 않는다.

## 정리 (OPS-01 `media` 단계)

만료 PENDING, FAILED, 상품(ACTIVE·ARCHIVED, 또는 바뀐 지 24시간이 안 된 DELETED)이 참조하지 않는 READY를 참조가 없어진 때(`unreferenced_since`)부터 24시간 뒤 지운다. `unreferenced_since`는 READY가 될 때 기록되고, 상품에 연결된 동안은 null이라 정리 index에 들어가지 않는다(B7이 연결 때 null, 해제·삭제 때 현재 시각으로 둔다). 행은 `DELETED` tombstone으로 남아 멱등 key가 계속 410을 돌려준다. 삭제 실패는 2분 lease 뒤 재시도한다. report에 `mediaDeleted`, `mediaDeleteFailed`를 더했다.
