package app.wishlist.android.feature.detail

import app.wishlist.android.navigation.PhotoHandoff

/** 사진 픽셀 크기. */
internal data class PhotoPixels(val width: Int, val height: Int)

/**
 * 상세 사진 요청의 자리표시(C5 spec §4): handoff로 받은 카드 이미지(`placeholder`)와 카드 요청의 memory cache key
 * (`placeholderMemoryCacheKey`, 보조 경로). key는 route 인자가 아니라 handoff(화면 사이 전환 상태)로 온다. handoff가
 * 없으면(홈 진입·프로세스 복원) 둘 다 없고 기본 placeholder 뒤에 일반 로드다.
 */
internal data class DetailPhotoStart<I, K>(val placeholder: I?, val placeholderMemoryCacheKey: K?)

internal fun <I : Any, K : Any> detailPhotoStart(held: PhotoHandoff.Held<I, K>?): DetailPhotoStart<I, K> =
    DetailPhotoStart(held?.image, held?.memoryCacheKey)

/**
 * 상세 사진 공유 요소 상자의 비율(높이/폭). 1:1 칸 안 여백 56에 원래 비율로 맞춘 사진 사각형이 공유 요소다(카드 사진이
 * 그 사각형으로 커진다). handoff 사진 크기를 먼저 써서 전환 도중 상세 크기 사진이 와도 목표 사각형이 튀지 않고, 그다음
 * 그려진 상세 사진 크기다. 둘 다 없으면 null: 칸 전체 자리표시가 공유 요소다(사진 없는 카드는 자리표시가 커진다).
 */
internal fun detailPhotoRatio(held: PhotoPixels?, drawn: PhotoPixels?): Float? =
    listOfNotNull(held, drawn).firstOrNull { it.width > 0 && it.height > 0 }?.let { it.height.toFloat() / it.width }
