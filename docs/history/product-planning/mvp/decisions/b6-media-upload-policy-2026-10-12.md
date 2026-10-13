# 제품·운영 결정: B6 사용자 이미지 업로드

> 상태: **확정** · 날짜: 2026-10-12 · 영역: **제품·서버**

B6 착수 대조에서 남은 미결정 규칙을 사용자에게 묻고 확정했다. 이 문서는 결정을 기록하며 구현 완료를 의미하지 않는다. 설계는 [B6 spec](../../../../superpowers/specs/2026-10-12-b6-media-upload-design.md)에서 관리한다. 사진 보관함 선택 자체와 클라이언트의 긴 변 2048px JPEG 압축은 이미 확정된 결정이다([클라이언트 로드맵](../../../../superpowers/specs/2026-10-05-client-implementation-roadmap-design.md), C8).

## 결정 — 사용자 확인 완료

1. **형식·크기.** `image/jpeg`만 받는다. 5MiB(5,242,880 byte) 이하이고, 긴 변 2048px 이하, 짧은 변 1px 이상이다. 서버는 업로드 완료 확인 때 실제 byte를 읽어 JPEG인지와 치수를 확인한다.
2. **저장소·업로드 방식.** Cloud Storage private bucket에 앱이 직접 올린다. MEDIA-01은 V4 signed PUT URL을 발급하며 Content-Type, `x-goog-if-generation-match: 0`, `x-goog-content-length-range: 1,5242880`을 서명에 포함한다(크기 범위 header는 2026-10-12 spec 리뷰에서 추가 확정. GCS가 signed PUT에서 이를 강제하는지는 B11에서 확인하고, 강제하지 않아도 completion 검사가 남는다). 객체 경로는 owner와 mediaId로 만든다. B6은 저장소 adapter, local fake 저장소, 테스트까지 만든다. 실제 서명 권한(signBlob)과 bucket 연결은 B11에서 확인한다.
3. **만료·재업로드.** upload URL은 15분 동안 유효하다. 만료 전에 같은 key로 다시 요청하면 같은 mediaId와 새 URL을 준다. 객체가 아직 없을 때 completion을 부르면 409이고 PENDING을 유지한다. 만료된 뒤에는 410이다. 실제 이미지 검증에 실패하면 FAILED로 바꾸고 객체를 지운다. 410과 FAILED 모두 새 key로 새 intent를 만들어야 한다. 객체는 처음 한 번만 써지므로 READY가 된 뒤에는 byte가 바뀌지 않는다.
4. **미참조 정리·공유.** 만료된 PENDING, FAILED, 그리고 상품·archive 어디서도 참조하지 않는 READY는 참조가 없어진 시점부터 24시간 뒤 OPS-01이 batch로 객체를 지운다. 한 media는 상품 하나만 참조한다(unique). archive snapshot의 참조는 따로 보존한다.
5. **읽기 URL.** MEDIA-02와 item·archive 응답의 기존 `imageUrl`에 1시간짜리 signed GET URL을 넣는다. 응답에 `imageMediaId`도 함께 넣어, 클라이언트가 바뀌는 URL 대신 mediaId를 이미지 캐시 key로 쓰게 한다.
6. **외부 이미지 보존.** 분석으로 얻은 외부 이미지는 URL 문자열만 보관하고 archive snapshot에도 URL을 복사한다. 링크가 깨지면 클라이언트가 placeholder를 보여 준다. 출시 뒤 실제로 깨지는 비율을 보고 다시 판단한다.
7. **EXIF.** C8 클라이언트가 회전을 적용한 뒤 다시 인코딩하면서 EXIF를 뺀다. 서버는 completion 때 APP0(썸네일 없는 JFIF)·APP2(`ICC_PROFILE`)·APP14(Adobe) 외의 APPn segment와 COM이 JPEG 어디에든 있거나, 처음 만난 EOI 뒤에 byte(MPF 보조 이미지 등)가 있으면 FAILED로 거절한다. 처음 결정은 APP1 Exif였다. 2026-10-12 spec 리뷰에서 XMP와 trailing 데이터에도 위치 정보가 들어갈 수 있다는 지적으로 범위를 넓혔고, 2026-10-13 사용자 리뷰에서 APP13(IPTC)·COM 등도 막도록 허용 목록 방식으로 확정했다.
8. **업로드 한도.** 만료되지 않은 PENDING intent가 owner당 10개면 새 intent를 429로 거절한다. 일일 한도는 두지 않는다.

## 판단 근거

- 클라이언트가 항상 2048px JPEG로 다시 인코딩하므로 서버가 받을 형식을 하나로 줄였다. 서버가 해석하는 형식이 적을수록 검증 경우와 디코더 공격 면이 줄어든다. JDK ImageIO만으로 검증할 수 있어 디코더 의존성을 추가하지 않는다.
- 직접 업로드는 API 인스턴스가 이미지 byte를 중계하지 않게 한다. 기존 [이미지 저장 설계](../../../../architecture/server/mvp-product-api-design.md#이미지-저장)의 권장안과 API 목록의 "intent의 저장소 URL" 계약을 그대로 따른다. 5MiB 이하 사진에는 resumable 업로드가 과하다.
- 생성 전용 조건(`if-generation-match: 0`)은 15분 안에 같은 URL로 덮어쓰는 것을 막는다. 그래서 검증한 byte와 READY 뒤 보이는 byte가 같다. 덮어쓰기를 허용하는 재업로드는 이 보장을 깨므로 택하지 않았다.
- 24시간 유예는 오래 열어 둔 편집 초안이나 사진을 바꾼 직후의 경합에서 mediaId가 사라지지 않게 한다. 공유 참조는 참조 수를 세야 해서 정리 조건이 복잡해지는데, 상품마다 사진을 고르는 지금 흐름에는 필요하지 않다.
- 1시간짜리 읽기 URL은 목록을 오래 열어 두어도 이미지가 잘 깨지지 않게 한다. 별도 조회 API를 만들지 않는다는 기존 계약도 지킨다. URL이 응답마다 바뀌므로 캐시 key는 mediaId로 고정한다.
- 외부 이미지를 복사하면 저장 비용, 외부 요청 위험(SSRF), 저작권 부담이 생긴다. 실제로 링크가 깨지는 비율을 모르는 상태에서 이를 감수하지 않는다.
- 사진의 GPS 위치가 저장되는 것은 개인정보 위험이다. 클라이언트 계약과 서버 검사로 이중으로 막는다. 서버에서 다시 인코딩하면 화질이 두 번 손실되고 completion이 무거워져 택하지 않았다.
- 동시 PENDING 한도는 정상 사용(편집 화면당 사진 1장)에는 걸리지 않고, 토큰 탈취나 버그로 intent를 대량 발급하는 것만 막는다. 일일 집계는 필요가 확인되면 추가한다.

## 후속 묶음에 넘기는 범위

- B7: ITEM-04·ITEM-07에서 mediaId를 상품에 연결하고 해제한다. 해제 시각을 기록하고, 상품을 삭제할 때 참조를 해제한다.
- B10: archive snapshot이 media와 외부 이미지 URL을 참조한다. 정리 작업의 참조 판정에 archive 참조를 추가한다.
- B11: 실제 bucket, signBlob 권한, 업로드와 읽기 URL 만료, 공개 로그의 signed URL 누출 여부를 확인한다.
