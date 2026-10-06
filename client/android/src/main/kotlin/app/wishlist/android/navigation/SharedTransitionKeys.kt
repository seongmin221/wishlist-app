package app.wishlist.android.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.DropShadowPainter
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.WishlistTokens
import app.wishlist.android.designsystem.WishlistTokens.Curve
import app.wishlist.android.designsystem.WishlistTokens.Motion

/**
 * 공유 요소 키. 누른 요소의 `sourceKey`(탭 안에서 유일, 예: `home/product/p1`)에 종류를 붙인다.
 * 탭 이름을 sourceKey 앞에 두어 탭 전환 중 다른 탭의 같은 요소와 이어지지 않게 한다.
 */
object SharedTransitionKeys {
    fun photo(sourceKey: String): String = "photo:$sourceKey"
    fun surface(sourceKey: String): String = "surface:$sourceKey"
}

/** 화면 이동 모션의 공유 범위. `WLNavHost`가 넣는다. */
internal val LocalWLSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** 지금 그리는 스택 칸의 전환 범위(push·pop 쪽). `WLNavHost`가 칸마다 넣는다. */
internal val LocalWLStackScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** 자리 표시 면의 시작 모습(누른 요소의 면 색·모서리). 원래 요소가 등록하고 다음 화면이 읽는다. */
internal data class WLSurfaceSpec(val color: Color, val radius: Dp)

internal class WLSurfaceRegistry {
    val specs = mutableStateMapOf<String, WLSurfaceSpec>()
    private val registrations = mutableMapOf<String, LinkedHashMap<Any, WLSurfaceSpec>>()

    fun register(key: String, owner: Any, spec: WLSurfaceSpec) {
        val sources = registrations.getOrPut(key) { linkedMapOf() }
        sources[owner] = spec
        val visible = sources.values.last()
        if (specs[key] != visible) specs[key] = visible
    }

    fun unregister(key: String, owner: Any) {
        val sources = registrations[key] ?: return
        sources.remove(owner)
        if (sources.isEmpty()) {
            registrations.remove(key)
            specs.remove(key)
        } else {
            val visible = sources.values.last()
            if (specs[key] != visible) specs[key] = visible
        }
    }
}

internal val LocalWLSurfaceRegistry = staticCompositionLocalOf<WLSurfaceRegistry> { error("WLNavHost is required") }

private val PhotoShape = RoundedCornerShape(WishlistTokens.Radius.m)

/** 사진 사각형: 열 때 420, 뒤로 360 `emphasized`. 커지는 쪽이면 열기다. */
private val PhotoBounds = BoundsTransform { initial, target ->
    tween(if (target.width >= initial.width) Motion.pushPhotoOpen else Motion.pushPhotoBack, easing = Curve.emphasized)
}

/**
 * 자리 표시 면 사각형. 열기: 떠오름(사방 3, 80 ease-out) → 화면 전체(420 emphasized).
 * 뒤로: 화면 전체 → 떠오른 사각형(360 emphasized) → 원래 요소(80 ease-in).
 */
private fun surfaceBounds(density: Density) = BoundsTransform { initial, target ->
    val outset = with(density) { Motion.pushSurfaceLiftOutset.dp.toPx() }
    if (target.width >= initial.width) {
        keyframes<Rect> {
            durationMillis = Motion.pushSurfaceLift + Motion.pushSurfaceExpand
            initial at 0 using Curve.easeOut
            initial.inflate(outset) at Motion.pushSurfaceLift using Curve.emphasized
        }
    } else {
        keyframes<Rect> {
            durationMillis = Motion.pushSurfaceBack + Motion.pushSurfaceSettle
            initial at 0 using Curve.emphasized
            target.inflate(outset) at Motion.pushSurfaceBack using Curve.easeIn
        }
    }
}

/**
 * 자리 표시 면의 단계 값 `s`: 0 = 원래 요소, 1 = 떠오름, 2 = 화면 전체. 위치(`surfaceBounds`)와 같은 시간표로 움직여
 * 색·모서리·그림자가 사각형과 맞는다. 되감기(predictive back)도 같은 Transition을 따라간다.
 */
private fun surfacePhaseSpec(expanding: Boolean): FiniteAnimationSpec<Float> =
    if (expanding) {
        keyframes {
            durationMillis = Motion.pushSurfaceLift + Motion.pushSurfaceExpand
            0f at 0 using Curve.easeOut
            1f at Motion.pushSurfaceLift using Curve.emphasized
        }
    } else {
        keyframes {
            durationMillis = Motion.pushSurfaceBack + Motion.pushSurfaceSettle
            2f at 0 using Curve.emphasized
            1f at Motion.pushSurfaceBack using Curve.easeIn
        }
    }

@Composable
private fun Transition<EnterExitState>.surfacePhase(restValue: Float, awayValue: Float): State<Float> =
    animateFloat(
        transitionSpec = {
            val from = if (initialState == EnterExitState.Visible) restValue else awayValue
            val to = if (targetState == EnterExitState.Visible) restValue else awayValue
            surfacePhaseSpec(expanding = to > from)
        },
        label = "surfacePhase",
    ) { if (it == EnterExitState.Visible) restValue else awayValue }

/**
 * `s`에 따른 면 그리기(색·모서리·떠오름 그림자). 글자·아이콘은 싣지 않는다.
 * `active = false`면 아무것도 그리지 않고 `s`도 읽지 않는다(공유 요소 자리만 둔다). 그래서 전환에 참여하지 않는 면은
 * 전환 동안 다시 그려지지도(recomposition), 그림자를 드리우지도 않는다.
 */
@Composable
private fun SurfacePlaceholder(modifier: Modifier, s: State<Float>, from: WLSurfaceSpec, to: WLSurfaceSpec, active: Boolean = true) {
    if (!active) {
        Box(modifier)
        return
    }
    // The public constructor owns no platform shape-key cache and evaluates the outline every draw.
    val painter = remember(s, from.radius, to.radius) {
        val shape = object : Shape {
            override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
                val expansion = (s.value - 1f).coerceIn(0f, 1f)
                return RoundedCornerShape(lerp(from.radius, to.radius, expansion)).createOutline(size, layoutDirection, density)
            }
        }
        DropShadowPainter(
            shape,
            Shadow(
                radius = Motion.pushSurfaceLiftShadowBlur.dp,
                color = Color.Black,
                offset = DpOffset(0.dp, Motion.pushSurfaceLiftShadowY.dp),
            ),
        )
    }
    Box(modifier.drawBehind {
        val phase = s.value
        val expansion = (phase - 1f).coerceIn(0f, 1f)
        val radius = lerp(from.radius, to.radius, expansion)
        val shadowAlpha = Motion.pushSurfaceLiftShadowAlpha *
            (if (phase <= 1f) phase else 2f - phase).coerceIn(0f, 1f)
        if (shadowAlpha > 0f) with(painter) { draw(size, alpha = shadowAlpha) }
        drawRoundRect(lerp(from.color, to.color, expansion), cornerRadius = CornerRadius(radius.toPx()))
    })
}

/**
 * 사진 공유 요소. 목록 카드 사진과 상세 사진에 같은 `sourceKey`로 붙인다. 모서리 20을 유지한다.
 * 전환 중 원래 사진 자리는 비고(그리지 않음), 사진은 공유 범위의 overlay에서 그려져 상세의 페이드를 타지 않는다.
 */
@Composable
fun Modifier.wlSharedPhoto(sourceKey: String): Modifier {
    val shared = LocalWLSharedScope.current ?: return this.clip(PhotoShape)
    val scope = LocalWLStackScope.current ?: return this.clip(PhotoShape)
    return with(shared) {
        this@wlSharedPhoto
            .sharedElement(
                rememberSharedContentState(SharedTransitionKeys.photo(sourceKey)),
                animatedVisibilityScope = scope,
                boundsTransform = PhotoBounds,
                clipInOverlayDuringTransition = OverlayClip(PhotoShape),
            )
            .clip(PhotoShape)
    }
}

/**
 * 사진 없는 요소(칩·카드·줄)를 누르면 커지는 자리 표시 면의 원래 쪽. 요소 뒤에 같은 모양의 면을 깔아 두고
 * 전환 동안에는 그 면만 overlay에서 커지며 요소(글자 포함)를 가린다. 요소의 면 색·모서리를 그대로 넘긴다.
 */
@Composable
fun WLSharedSurfaceSource(
    sourceKey: String,
    color: Color,
    radius: Dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val shared = LocalWLSharedScope.current
    val scope = LocalWLStackScope.current
    val registry = LocalWLSurfaceRegistry.current
    val spec = WLSurfaceSpec(color, radius)
    val owner = remember(registry, sourceKey) { Any() }
    SideEffect { registry.register(sourceKey, owner, spec) }
    DisposableEffect(registry, sourceKey, owner) {
        onDispose { registry.unregister(sourceKey, owner) }
    }
    Box(modifier) {
        if (shared != null && scope != null) {
            val density = LocalDensity.current
            val bounds = remember(density) { surfaceBounds(density) }
            val bg = LocalWLColors.current.background
            with(shared) {
                val state = rememberSharedContentState(SharedTransitionKeys.surface(sourceKey))
                // 누른 요소(다음 화면과 키가 맞은 면)만 그린다. 같은 화면의 다른 칩·카드도 같은 칸 전환(push·pop)을 타지만
                // 짝이 없으니 그리지 않는다. 짝이 맞으면 면은 공유 범위의 overlay에서 떠올라 화면 전체로 커진다.
                val sharedModifier = Modifier
                    .matchParentSize()
                    .sharedElement(state, animatedVisibilityScope = scope, boundsTransform = bounds)
                if (state.isMatchFound) {
                    val phase = scope.transition.surfacePhase(restValue = 0f, awayValue = 2f)
                    SurfacePlaceholder(sharedModifier, phase, from = spec, to = WLSurfaceSpec(bg, 0.dp))
                } else {
                    // Keep only the matching probe; unrelated cards have no animated float or painter.
                    Box(sharedModifier)
                }
            }
        }
        content()
    }
}

/**
 * 자리 표시 면으로 열리는 화면의 바탕. 면이 화면 전체로 커진 뒤 다음 화면 바탕색이 되고, 내용은
 * 커짐 시작 후 190부터 230 동안 나타난다(뒤로: 180 동안 사라짐). 내용은 전환 중 overlay에서 면 위에 그린다.
 */
@Composable
fun WLSurfaceScreen(sourceKey: String?, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = LocalWLColors.current
    val shared = LocalWLSharedScope.current
    val scope = LocalWLStackScope.current
    if (shared == null || scope == null || sourceKey == null) {
        Box(modifier.fillMaxSize().background(c.background), content = content)
        return
    }
    val registry = LocalWLSurfaceRegistry.current
    val registered = registry.specs[sourceKey]
    val retained = remember(sourceKey) { SurfaceSpecFallback() }
    val from = retained.resolve(registered, WLSurfaceSpec(c.card, WishlistTokens.Radius.l))
    val density = LocalDensity.current
    val bounds = remember(density) { surfaceBounds(density) }
    val s = scope.transition.surfacePhase(restValue = 2f, awayValue = 0f)
    val contentAlpha by scope.transition.animateFloat(
        transitionSpec = {
            if (targetState == EnterExitState.Visible) {
                tween(Motion.pushSurfaceContent, delayMillis = Motion.pushSurfaceLift + Motion.pushSurfaceContentDelay, easing = Curve.easeOut)
            } else {
                tween(Motion.pushSurfaceBackContent, easing = Curve.easeIn)
            }
        },
        label = "surfaceContent",
    ) { if (it == EnterExitState.Visible) 1f else 0f }
    Box(modifier.fillMaxSize()) {
        with(shared) {
            SurfacePlaceholder(
                Modifier
                    .fillMaxSize()
                    .sharedElement(
                        rememberSharedContentState(SharedTransitionKeys.surface(sourceKey)),
                        animatedVisibilityScope = scope,
                        boundsTransform = bounds,
                    ),
                s = s,
                from = from,
                to = WLSurfaceSpec(c.background, 0.dp),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .renderInSharedTransitionScopeOverlay(zIndexInOverlay = 1f)
                    .graphicsLayer { alpha = contentAlpha },
                content = content,
            )
        }
    }
}

/** Keep the most recent live source appearance when its composition leaves during a transition. */
internal class SurfaceSpecFallback {
    private var last: WLSurfaceSpec? = null

    fun resolve(registered: WLSurfaceSpec?, fallback: WLSurfaceSpec): WLSurfaceSpec {
        if (registered != null) last = registered
        return registered ?: last ?: fallback
    }
}
