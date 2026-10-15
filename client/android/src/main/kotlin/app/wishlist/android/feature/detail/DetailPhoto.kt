package app.wishlist.android.feature.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.wishlist.android.navigation.LocalPhotoHandoff
import app.wishlist.android.navigation.LocalWLEntry
import app.wishlist.android.navigation.LocalWLNavigator
import app.wishlist.android.navigation.PhotoHandoff
import app.wishlist.android.navigation.PhotoTransitionDebug
import app.wishlist.android.designsystem.WishlistTokens
import app.wishlist.android.designsystem.WishlistTokens.Motion
import app.wishlist.android.navigation.photoCorner
import app.wishlist.android.navigation.wlSharedPhoto
import app.wishlist.shared.domain.DetailKind
import coil3.Image
import coil3.compose.AsyncImage
import coil3.compose.asPainter
import coil3.request.ImageRequest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

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

private fun Image.pixels() = PhotoPixels(width, height)

/**
 * 카드에서 사진 공유 요소로 연 상세의 사진 자리(C5 spec §4). 1:1 칸 안 여백 56에 원래 비율로 맞춘 사진 사각형이
 * [sourceKey] 공유 요소다(모서리는 카드 20 → 상세 0으로 날아가며 바뀌고, 멈추면 C4 모습).
 * - 그리는 순서: handoff 카드 이미지(요청의 `placeholder`) → 카드 요청의 memory cache key(`placeholderMemoryCacheKey`) →
 *   기본 placeholder. 상세 크기 사진이 오면 바꾼다. 카드 이미지가 없으면(아직 로딩 중이던 카드) 칸 전체 자리표시가 같은
 *   공유 요소로 커진다.
 * - 상세 크기 사진이 그려지고 push 모션이 끝나면 handoff를 놓는다. 지금 그리는 이미지는 [PhotoHandoff.showing]으로 알려
 *   뒤로 갈 때 카드 쪽이 그것을 그린다.
 * - [imageUrl]이 null이면(항목을 아직 받는 중) handoff 이미지만 그린다.
 */
@Composable
internal fun DetailSharedPhoto(imageUrl: String?, sourceKey: String) {
    val context = LocalContext.current
    val handoff = LocalPhotoHandoff.current
    val nav = LocalWLNavigator.current
    val entry = LocalWLEntry.current
    val held = entry?.let { handoff.forward(it.id) }
    var drawn by remember { mutableStateOf<Image?>(null) }
    val url = (photoSourceFor(imageUrl) as? PhotoSource.Remote)?.url
    val ratio = detailPhotoRatio(held?.image?.pixels(), drawn?.pixels())

    if (entry != null) {
        LaunchedEffect(entry.id) {
            snapshotFlow { nav.activeTransition == null }.first { it }
            handoff.motionEnded(entry.id)
        }
        val current = drawn ?: held?.image
        LaunchedEffect(entry.id, current) { current?.let { handoff.showing(entry.id, sourceKey, it) } }
        DisposableEffect(entry.id) { onDispose { handoff.notShowing(entry.id) } }
        LaunchedEffect(entry.id) {
            snapshotFlow { PhotoHandoff.isLeaving(nav.activeTransition, entry.tab, entry.id, nav.entries(entry.tab).map { it.id }) }
                .distinctUntilChanged()
                .collect { if (it) PhotoTransitionDebug.onPhotoTransitionStart(context) }
        }
    }

    Box(Modifier.fillMaxSize().padding(if (ratio != null) 56.dp else 0.dp), contentAlignment = Alignment.Center) {
        val box = if (ratio != null) Modifier.aspectRatio(1f / ratio, matchHeightConstraintsFirst = ratio > 1f) else Modifier.fillMaxSize()
        // Corner 20 (the card's) while flying, 0 at rest like the board's detail photo.
        val corner = photoCorner(atRest = 0.dp, entering = WishlistTokens.Radius.m, millis = Motion.pushPhotoOpen)
        Box(box.wlSharedPhoto(sourceKey, corner)) {
            if (held == null && drawn == null) PhotoPlaceholder(Modifier.fillMaxSize())
            if (url != null) {
                // Built once per address with the photo held at the push: letting go of it later must not reload.
                val request = remember(url) {
                    val start = detailPhotoStart(held)
                    ImageRequest.Builder(context).data(url)
                        .placeholder(start.placeholder)
                        .placeholderMemoryCacheKey(start.placeholderMemoryCacheKey)
                        .build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    onSuccess = {
                        drawn = it.result.image
                        entry?.let { e -> handoff.detailDrawn(e.id) }
                    },
                )
            } else if (held != null) {
                androidx.compose.foundation.Image(remember(held.image) { held.image.asPainter(context) }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
    }
}

/**
 * 카드에서 연 상세(`photoEntry`)가 사진 자리 하나를 처음 프레임(항목을 받는 중)부터 상품 표시까지 같은 노드로 두는지.
 * 공유 요소의 목표가 날아가는 도중 다른 노드로 바뀌면 움직이지 않고 원래 자리에 남았다가 튄다(2026-10-15 에뮬레이터).
 * 항목 없는 오류·분석 중·삭제됨에는 사진 칸이 없다.
 */
internal fun keepsSharedPhotoSlot(photoEntry: Boolean, hasItem: Boolean, hasError: Boolean, kind: DetailKind?): Boolean = when {
    !photoEntry -> false
    !hasItem -> !hasError
    else -> kind == DetailKind.READY || kind == DetailKind.INCOMPLETE
}
