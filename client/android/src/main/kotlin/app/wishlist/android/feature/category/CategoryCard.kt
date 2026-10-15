package app.wishlist.android.feature.category

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.foundation.Image
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wishlist.android.R
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.PriceText
import app.wishlist.android.designsystem.PurposeDot
import app.wishlist.android.designsystem.WLIcon
import app.wishlist.android.designsystem.WLLineIcon
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.WLType
import app.wishlist.android.designsystem.WishlistTokens
import app.wishlist.android.feature.detail.DetailText
import app.wishlist.android.feature.detail.PhotoSource
import app.wishlist.android.feature.detail.ProductPhoto
import app.wishlist.android.feature.detail.photoSourceFor
import app.wishlist.android.feature.home.resolve
import app.wishlist.shared.domain.CardMetaLine
import app.wishlist.shared.domain.PurposeLabel
import app.wishlist.shared.model.WishlistItem
import app.wishlist.android.navigation.LocalPhotoHandoff
import app.wishlist.android.navigation.LocalWLEntry
import app.wishlist.android.navigation.LocalWLNavigator
import app.wishlist.android.navigation.PhotoHandoff
import app.wishlist.android.designsystem.WishlistTokens.Motion
import app.wishlist.android.navigation.photoCorner
import app.wishlist.android.navigation.wlSharedPhoto
import coil3.Image
import coil3.SingletonImageLoader
import coil3.compose.asPainter
import coil3.memory.MemoryCache
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import coil3.size.Scale

/**
 * One list's photos (D10): every card photo is requested once, at most six at a time ([PhotoRequestGate]),
 * decoded to fit the column width × 3 column widths (ratio kept, [PhotoPagePlanner.decodeBox]). The
 * answer's pixel size gives the card ratio; the request is the same one the card draws, so the card
 * reads it from Coil's memory cache. A failed or missing photo is [PhotoPagePlanner.Photo.Failed]. The
 * sizes go to the list entry's [CategoryGridLayout.photos] (they outlive the composition).
 */
internal class CategoryPhotoBook(private val context: Context, private val photos: MutableMap<String, PhotoPagePlanner.Photo>) {
    private val gate = PhotoRequestGate()

    fun request(url: String, columnWidthPx: Int): ImageRequest {
        val (w, h) = PhotoPagePlanner.decodeBox(columnWidthPx)
        return ImageRequest.Builder(context).data(url).size(w, h).scale(Scale.FIT).precision(Precision.EXACT).build()
    }

    /** Reads [item]'s photo size (once). Runs in the caller's scope: leaving the screen cancels it. */
    suspend fun measure(item: WishlistItem, columnWidthPx: Int) {
        if (item.id in photos) return
        val url = (photoSourceFor(item.product.imageUrl) as? PhotoSource.Remote)?.url
        if (url == null) {
            photos[item.id] = PhotoPagePlanner.Photo.Failed
            return
        }
        val result = gate.run { SingletonImageLoader.get(context).execute(request(url, columnWidthPx)) }
        photos[item.id] = (result as? SuccessResult)?.image?.let { PhotoPagePlanner.Photo.Sized(it.width, it.height) }
            ?: PhotoPagePlanner.Photo.Failed
    }
}

private val PhotoShape = RoundedCornerShape(WishlistTokens.Radius.m)

/** 카드가 지금 그리는 사진과 그 요청의 memory cache key. 카드를 누르면 상세로 넘긴다([PhotoHandoff]). */
internal data class ShownPhoto(val image: Image, val memoryCacheKey: MemoryCache.Key?)

/**
 * 사진 상품 카드(FCategoryList): 사진(모서리 20, [ratio] = 높이/폭) + 8 + 이름 14 · 4 · 가격 18/700(없으면 숨김) · 4 ·
 * 목적 점 + "목적 이름 · N일 전 확인"(D8; 확인 시각이 없으면 목적만). 미확정 검토는 사진 왼쪽 위 ✳(D13).
 * 사진 칸은 [sourceKey]로 상세 사진과 이어지는 공유 요소다(C5 PR B). 누르면 지금 그리는 사진을 [onClick]으로 넘기고,
 * 그 상세가 뒤로 떠나는 동안에는 상세가 그리던 사진([PhotoHandoff.reverseFor])을 먼저 그린다.
 */
@Composable
internal fun CategoryCard(
    item: WishlistItem,
    ratio: Float,
    meta: CardMetaLine,
    unconfirmed: Boolean,
    photoRequest: ImageRequest?,
    sourceKey: String,
    onClick: (ShownPhoto?) -> Unit,
) {
    val c = LocalWLColors.current
    val unconfirmedLabel = stringResource(R.string.category_card_unconfirmed)
    var shown by remember(photoRequest) { mutableStateOf<ShownPhoto?>(null) }
    val reverse = reversePhoto(sourceKey)
    Column(
        Modifier.fillMaxWidth().clickable(role = Role.Button) { onClick(shown) },
        verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8),
    ) {
        // Coming back from its detail, the card's corner grows 0 → 20 with the shrinking photo (the overlay draws this side).
        val corner = if (reverse != null) {
            photoCorner(atRest = WishlistTokens.Radius.m, entering = 0.dp, millis = Motion.pushPhotoBack)
        } else WishlistTokens.Radius.m
        Box(Modifier.fillMaxWidth().aspectRatio(1f / ratio).wlSharedPhoto(sourceKey, corner)) {
            if (photoRequest != null) {
                ProductPhotoRequest(photoRequest, Modifier.fillMaxSize()) { shown = it }
            } else {
                ProductPhoto(null, Modifier.fillMaxSize())
            }
            if (reverse != null) {
                val context = LocalContext.current
                Image(remember(reverse) { reverse.asPainter(context) }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            if (unconfirmed) {
                Box(
                    Modifier
                        .padding(WishlistTokens.Space.s8)
                        .size(32.dp)
                        .clip(RoundedCornerShape(WishlistTokens.Radius.xs))
                        .background(Color.White)
                        .semantics { contentDescription = unconfirmedLabel },
                    contentAlignment = Alignment.Center,
                ) { WLIcon(WLLineIcon.Pending, size = 16.dp, color = Color(0xFF1D1D1D)) }
            }
        }
        Column(Modifier.padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s4)) {
            WLText(keepAllBreaks(item.product.name.orEmpty()), WLType.body)
            PriceText(item.product.price?.canonical, item.product.currency)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val purpose = meta.purpose
                if (purpose is PurposeLabel.Named) {
                    val color = DetailText.purposeColor(purpose.colorKey)
                    if (color != null) PurposeDot(color) else Box(Modifier.size(10.dp).background(c.textSecondary, CircleShape))
                }
                val purposeText = CategoryText.purposePart(meta)?.let { stringResource(it) } ?: (purpose as PurposeLabel.Named).name
                val text = CategoryText.checkedPart(meta)?.let { "$purposeText · ${it.resolve()}" } ?: purposeText
                WLText(text, WLType.label, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The card photo from the measured request (memory-cache hit); the placeholder stays under it until drawn. */
@Composable
private fun ProductPhotoRequest(request: ImageRequest, modifier: Modifier, onShown: (ShownPhoto) -> Unit) {
    Box(modifier) {
        ProductPhoto(null, Modifier.fillMaxSize())
        AsyncImage(
            model = request,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            onSuccess = { onShown(ShownPhoto(it.result.image, it.result.memoryCacheKey)) },
        )
    }
}

/**
 * While the detail this card opened goes back (pop or drag back, also while a cancelled drag returns),
 * the image that detail draws — read from snapshot state in this composition, so the first back frame
 * already has it.
 */
@Composable
private fun reversePhoto(sourceKey: String): Image? {
    val handoff = LocalPhotoHandoff.current
    val nav = LocalWLNavigator.current
    val entry = LocalWLEntry.current ?: return null
    val transition = nav.activeTransition
    val stack = nav.entries(entry.tab).map { it.id }
    return handoff.reverseFor(sourceKey) { PhotoHandoff.isLeaving(transition, entry.tab, it, stack) }
}

/** Skeleton card while its page is not laid out (D10): 1:1 card-color face + name/price bars, no motion. */
@Composable
internal fun CategorySkeletonCard() {
    val c = LocalWLColors.current
    val loading = stringResource(R.string.category_loading)
    Column(
        Modifier.fillMaxWidth().semantics { contentDescription = loading },
        verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(PhotoShape).background(c.card).clearAndSetSemantics { })
        Column(Modifier.padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SkeletonBar(110.dp)
            SkeletonBar(80.dp, height = 18.dp)
            SkeletonBar(96.dp, height = 12.dp)
        }
    }
}

/** Header count style: 20/700 tabular secondary (보드 목록 머리 숫자). */
internal val ListCountStyle = WLType.price.copy(fontSize = 20.sp)
