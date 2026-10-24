package app.wishlist.android.feature.flow

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import app.wishlist.android.R
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.LocalWLDark
import app.wishlist.android.designsystem.PriceText
import app.wishlist.android.designsystem.PurposeDot
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.WLType
import app.wishlist.android.designsystem.WishlistTokens
import app.wishlist.android.feature.detail.DetailText
import app.wishlist.android.feature.detail.ProductPhoto
import app.wishlist.android.feature.purpose.purposeCandidateTitleFallback
import app.wishlist.shared.model.WishlistItem
import app.wishlist.shared.presentation.HomeFlowCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 보드 뒤 카드 색(토큰에 없는 값): 바로 뒤 #EEEEEE/#2A2A2A, 그 뒤 #E4E4E4/#262626. */
private val BackNearLight = Color(0xFFEEEEEE)
private val BackNearDark = Color(0xFF2A2A2A)
private val BackFarLight = Color(0xFFE4E4E4)
private val BackFarDark = Color(0xFF262626)

private val CardShape = RoundedCornerShape(WishlistTokens.Radius.l)

/** A card flying out: thrown from its drag position ([fromX] dp, [fromRotation]°) towards [direction]. */
@Immutable
internal data class LeavingCard(val card: HomeFlowCard, val direction: ThrowDecision, val fromX: Float, val fromRotation: Float)

/**
 * The review stack's motion state (motion.md §4). [throwCard] starts the exit of the top card, records the
 * pass right away (the counter changes at the start) and locks input until the 340 ms exit ends. The next
 * stack comes forward 40 ms later over 300 `emphasized`.
 */
@Stable
internal class ReviewStackMotion(private val scope: CoroutineScope, private val onPass: () -> Unit) {
    var leaving by mutableStateOf<LeavingCard?>(null)
        private set

    /** The top card's finger offset (dp). */
    val dragX = Animatable(0f)
    val exit = Animatable(0f)
    val nextScale = Animatable(1f)
    val nextOffset = Animatable(0f)

    val locked: Boolean get() = leaving != null

    /** The card width W (dp), measured by the stack. */
    var widthDp = 0f

    fun throwCard(card: HomeFlowCard, direction: ThrowDecision) {
        if (locked || direction == ThrowDecision.Return) return
        val fromX = dragX.value
        leaving = LeavingCard(card, direction, fromX, CardThrowMath.rotationFor(fromX, widthDp))
        onPass()
        scope.launch {
            dragX.snapTo(0f)
            exit.snapTo(0f)
            exit.animateTo(1f, tween(CardThrowMath.remainingMillis(fromX), easing = WishlistTokens.Curve.throwAway))
            leaving = null
        }
        scope.launch {
            nextScale.snapTo(CardThrowMath.NEXT_START_SCALE)
            nextOffset.snapTo(CardThrowMath.NEXT_START_OFFSET)
            delay(CardThrowMath.NEXT_DELAY_MILLIS.toLong())
            launch { nextScale.animateTo(1f, tween(CardThrowMath.NEXT_MILLIS, easing = WishlistTokens.Curve.emphasized)) }
            nextOffset.animateTo(0f, tween(CardThrowMath.NEXT_MILLIS, easing = WishlistTokens.Curve.emphasized))
        }
    }

    fun drag(byDp: Float) {
        if (locked) return
        scope.launch { dragX.snapTo(dragX.value + byDp) }
    }

    /** Released: throw past the threshold, else back in place (250 `emphasized`). */
    fun release(card: HomeFlowCard?, velocityDp: Float) {
        if (locked) return
        val decision = CardThrowMath.decide(dragX.value, velocityDp, widthDp)
        if (card != null && decision != ThrowDecision.Return) {
            throwCard(card, decision)
        } else {
            scope.launch { dragX.animateTo(0f, tween(CardThrowMath.RETURN_MILLIS, easing = WishlistTokens.Curve.emphasized)) }
        }
    }
}

/**
 * 쌓인 카드: 뒤 카드 최대 2장(남은 수만큼, -2.5°·3°, 위치 차 8·14) 위에 현재 카드. 현재 카드는 손가락을 따라
 * `translateX(dx)`, `rotate(dx / W × 16°)`(회전 중심 가로 50%·세로 90%)로 움직인다. 나가는 카드는 묶음 위에 따로 그린다.
 */
@Composable
internal fun ReviewCardStack(current: HomeFlowCard?, upcoming: Int, motion: ReviewStackMotion, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val widthDp = maxWidth.value
        motion.widthDp = widthDp
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = motion.nextScale.value
                    scaleY = motion.nextScale.value
                    translationY = motion.nextOffset.value * density.density
                },
        ) {
            if (upcoming >= 2) BackCard(far = true)
            if (upcoming >= 1) BackCard(far = false)
            // While the pass is on its way the top card is still the leaving one: do not draw it twice.
            val top = current?.takeIf { it.item.id != motion.leaving?.card?.item?.id }
            if (top != null) {
                val velocity = remember(top.item.id) { VelocityTracker() }
                ReviewCard(
                    top.item,
                    Modifier
                        .graphicsLayer {
                            translationX = motion.dragX.value * density.density
                            rotationZ = CardThrowMath.rotationFor(motion.dragX.value, widthDp)
                            transformOrigin = TransformOrigin(0.5f, 0.9f)
                        }
                        .pointerInput(top.item.id) {
                            detectHorizontalDragGestures(
                                onDragStart = { velocity.resetTracking() },
                                onDragEnd = { motion.release(top, velocity.calculateVelocity().x / density.density) },
                                onDragCancel = { motion.release(null, 0f) },
                            ) { change, amount ->
                                velocity.addPosition(change.uptimeMillis, change.position)
                                change.consume()
                                motion.drag(amount / density.density)
                            }
                        },
                )
            }
        }
        motion.leaving?.let { leaving ->
            val sign = if (leaving.direction == ThrowDecision.Right) 1f else -1f
            ReviewCard(
                leaving.card.item,
                Modifier.graphicsLayer {
                    val p = motion.exit.value
                    translationX = lerp(leaving.fromX, sign * CardThrowMath.EXIT_DISTANCE, p) * density.density
                    rotationZ = lerp(leaving.fromRotation, sign * CardThrowMath.MAX_ROTATION, p)
                    transformOrigin = TransformOrigin(0.5f, 0.9f)
                },
            )
        }
    }
}

/** 뒤 카드(내용 없는 면): 바로 뒤는 위 8·좌우 2·아래 -2에 -2.5°, 그 뒤는 위 14·좌우 6·아래 -6에 3°. */
@Composable
private fun BackCard(far: Boolean) {
    val dark = LocalWLDark.current
    val color = when {
        far && dark -> BackFarDark
        far -> BackFarLight
        dark -> BackNearDark
        else -> BackNearLight
    }
    Box(
        Modifier
            .inset(top = if (far) 14.dp else 8.dp, horizontal = if (far) 6.dp else 2.dp, bottomOutset = if (far) 6.dp else 2.dp)
            .graphicsLayer { rotationZ = if (far) 3f else -2.5f }
            .clip(CardShape)
            .background(color),
    )
}

/** CSS `inset: top h -bottom`: fills the parent less [top] and [horizontal], and reaches [bottomOutset] below it. */
private fun Modifier.inset(top: Dp, horizontal: Dp, bottomOutset: Dp): Modifier = layout { measurable, constraints ->
    val w = (constraints.maxWidth - 2 * horizontal.roundToPx()).coerceAtLeast(0)
    val h = (constraints.maxHeight - top.roundToPx() + bottomOutset.roundToPx()).coerceAtLeast(0)
    val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(w, h))
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(horizontal.roundToPx(), top.roundToPx()) }
}

/**
 * 리뷰 카드(보드 article): 카드색 + 1.5 선, 안 여백 12. 1:1 사진(모서리 20, 여백 32에 원래 비율) · 이름 18/700 · 가격 20/700 ·
 * 카테고리·목적 줄(`변경`은 C8 비활성) · AI 설명 한 줄. 중복 비교 줄(C10)은 그리지 않는다. 화면이 낮으면 사진이 줄어든다.
 */
@Composable
internal fun ReviewCard(item: WishlistItem, modifier: Modifier = Modifier) {
    val c = LocalWLColors.current
    Column(
        modifier
            .fillMaxSize()
            .clip(CardShape)
            .background(c.card)
            .border(1.5.dp, c.line, CardShape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            ProductPhoto(
                item.product.imageUrl,
                Modifier.aspectRatio(1f, matchHeightConstraintsFirst = true).clip(RoundedCornerShape(WishlistTokens.Radius.m)),
                contentScale = ContentScale.Fit,
                imagePadding = 32.dp,
            )
        }
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val fallback = purposeCandidateTitleFallback(item.product.name, item.analysis.status)
            WLText(
                fallback?.let { stringResource(it) } ?: item.product.name.orEmpty(),
                WLType.bodyStrong.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 1.3.em),
                color = if (fallback != null) c.textSecondary else c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            PriceText(item.product.price?.canonical, item.product.currency, style = WLType.price.copy(fontSize = 20.sp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val categoryName = item.category.name
            ReviewFieldRow(stringResource(R.string.flow_review_category)) {
                WLText(
                    categoryName ?: stringResource(R.string.home_preview_no_category), ReviewValue,
                    color = if (categoryName != null) c.text else c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            val purposeName = item.purpose.name?.takeIf { item.purpose.id != null }
            ReviewFieldRow(stringResource(R.string.flow_review_purpose)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (purposeName != null) DetailText.purposeColor(item.purpose.colorKey)?.let { PurposeDot(it) }
                    WLText(
                        purposeName ?: stringResource(R.string.category_card_no_purpose), ReviewValue,
                        color = if (purposeName != null) c.text else c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.CenterStart) {
            HomeFlowText.aiLine(item.category.source, item.purpose.source)?.let {
                WLText(stringResource(it), WLType.body.copy(fontSize = 13.sp, lineHeight = 1.4.em), Modifier.padding(horizontal = 4.dp), color = c.textSecondary)
            }
        }
    }
}

private val ReviewValue = WLType.bodyStrong.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold)

/** 카드 안 줄: 묶음 면 위 13 보조색 이름(최소 52) · 15/700 값 · `변경`(14 보조색, C8 비활성 — 흐림·"사용할 수 없음"). */
@Composable
private fun ReviewFieldRow(label: String, value: @Composable () -> Unit) {
    val c = LocalWLColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = WishlistTokens.Space.minTouch)
            .clip(RoundedCornerShape(WishlistTokens.Radius.s))
            .background(c.sheetField)
            .padding(start = 14.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WLText(label, WLType.body.copy(fontSize = 13.sp), Modifier.widthIn(min = 52.dp), color = c.textSecondary, maxLines = 1)
        Box(Modifier.weight(1f)) { value() }
        Box(Modifier.heightIn(min = WishlistTokens.Space.minTouch).unavailable().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            WLText(stringResource(R.string.flow_review_change), WLType.body, color = c.textSecondary, maxLines = 1)
        }
    }
}

/** Keeps the stack's motion across recompositions of the flow screen. */
@Composable
internal fun rememberReviewStackMotion(onPass: () -> Unit): ReviewStackMotion {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val latest = androidx.compose.runtime.rememberUpdatedState(onPass)
    return androidx.compose.runtime.remember(scope) { ReviewStackMotion(scope) { latest.value() } }
}

/** Resets a drag left over when the top card changed without a throw (foreground window). */
@Composable
internal fun ResetDragOnSwap(current: HomeFlowCard?, motion: ReviewStackMotion) {
    LaunchedEffect(current?.item?.id) { if (!motion.locked) motion.dragX.snapTo(0f) }
}
