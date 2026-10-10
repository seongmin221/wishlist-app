package app.wishlist.android.feature.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.wishlist.android.designsystem.LocalWLColors
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import java.net.URI

/** 상품 사진을 무엇으로 그릴지. 주소가 쓸 만하면 Remote, 아니면 Placeholder(D9). */
sealed interface PhotoSource {
    data object Placeholder : PhotoSource
    data class Remote(val url: String) : PhotoSource
}

/** http/https이고 host가 있는 주소만 Remote. null·빈 값·깨진 주소·다른 scheme은 Placeholder. */
fun photoSourceFor(url: String?): PhotoSource {
    val trimmed = url?.trim().orEmpty()
    if (trimmed.isEmpty()) return PhotoSource.Placeholder
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return PhotoSource.Placeholder
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return PhotoSource.Placeholder
    if (uri.host.isNullOrEmpty()) return PhotoSource.Placeholder
    return PhotoSource.Remote(trimmed)
}

// 중립 상품 아이콘(24 격자 가방 윤곽). 디자인시스템에 상품 아이콘이 없어 여기서만 그린다.
private const val BagPath = "M6 8h12l1 12H5zM9 8V7a3 3 0 0 1 6 0v1"
private val bagPath by lazy { PathParser().parsePathString(BagPath).toPath() }

@Composable
private fun PhotoPlaceholder(modifier: Modifier) {
    val c = LocalWLColors.current
    Box(modifier.background(c.card), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(32.dp)) {
            val k = size.width / 24f
            scale(k, k, pivot = Offset.Zero) {
                drawPath(bagPath, c.textSecondary, style = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/**
 * 주소가 없거나 로딩·실패이면 카드색 면 + 중립 상품 아이콘. 장식이라 접근성 설명은 부모가 붙인다.
 * 사진은 `contentScale`로 그린다(기본 Crop: 칸을 채운다). 상세처럼 사진을 원래 비율로 여백 안에 넣을 때는 `Fit`과
 * `imagePadding`을 준다. 자리표시는 여백 없이 칸 전체에 그대로 남고, 사진이 그려진 뒤에만 사라진다.
 */
@Composable
fun ProductPhoto(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    imagePadding: Dp = 0.dp,
) {
    when (val source = photoSourceFor(imageUrl)) {
        PhotoSource.Placeholder -> PhotoPlaceholder(modifier)
        is PhotoSource.Remote -> {
            var loaded by remember(source.url) { mutableStateOf(false) }
            Box(modifier) {
                if (!loaded) PhotoPlaceholder(Modifier.fillMaxSize())
                AsyncImage(
                    model = source.url,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().padding(imagePadding),
                    contentScale = contentScale,
                    onState = { loaded = it is AsyncImagePainter.State.Success },
                )
            }
        }
    }
}
