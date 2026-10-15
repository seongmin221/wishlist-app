package app.wishlist.android.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateDp
import androidx.compose.runtime.getValue
import app.wishlist.android.designsystem.WishlistTokens
import app.wishlist.android.designsystem.WishlistTokens.Curve
import app.wishlist.android.designsystem.WishlistTokens.Motion

/**
 * 공유 요소 키. 누른 요소의 `sourceKey`(탭 안에서 유일, 예: `home/product/p1`)에 종류를 붙인다.
 * 탭 이름을 sourceKey 앞에 두어 탭 전환 중 다른 탭의 같은 요소와 이어지지 않게 한다.
 * 사진이 있는 이동만 공유 요소를 쓴다(사진 없는 이동은 가로 밀기, `WLPushStyle.Slide`).
 */
object SharedTransitionKeys {
    fun photo(sourceKey: String): String = "photo:$sourceKey"
}

/** 화면 이동 모션의 공유 범위. `WLNavHost`가 넣는다. */
internal val LocalWLSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** 지금 그리는 스택 칸의 전환 범위(push·pop 쪽). `WLNavHost`가 칸마다 넣는다. */
internal val LocalWLStackScope = compositionLocalOf<AnimatedVisibilityScope?> { null }


/** 사진 사각형: 열 때 420, 뒤로 360 `emphasized`. 커지는 쪽이면 열기다. */
private val PhotoBounds = BoundsTransform { initial, target ->
    tween(if (target.width >= initial.width) Motion.pushPhotoOpen else Motion.pushPhotoBack, easing = Curve.emphasized)
}

/**
 * 사진 공유 요소. 목록 카드 사진과 상세 사진에 같은 `sourceKey`로 붙인다. 모서리는 [corner](기본 20)로 자른다.
 * 전환 중 원래 사진 자리는 비고(그리지 않음), 사진은 공유 범위의 overlay에서 그려져 상세의 페이드를 타지 않는다.
 * overlay에는 목표 쪽 내용이 그려지므로 모서리 변화는 각 쪽이 [photoCorner]로 맡는다.
 */
@Composable
fun Modifier.wlSharedPhoto(sourceKey: String, corner: Dp = WishlistTokens.Radius.m): Modifier {
    val shape = RoundedCornerShape(corner)
    val rest = Modifier.clip(shape)
    val shared = LocalWLSharedScope.current ?: return this.then(rest)
    val scope = LocalWLStackScope.current ?: return this.then(rest)
    return with(shared) {
        this@wlSharedPhoto
            .sharedElement(
                rememberSharedContentState(SharedTransitionKeys.photo(sourceKey)),
                animatedVisibilityScope = scope,
                boundsTransform = PhotoBounds,
                // The overlay clips the flying photo too: the same (animated) corner, or it stays 20 and snaps at the end.
                clipInOverlayDuringTransition = OverlayClip(shape),
            )
            .then(rest)
    }
}

/**
 * 들어오는 쪽 사진의 모서리: 이 칸이 들어오기 전 [entering]에서 보일 때 [atRest]로, 들어오는 전환(사진 사각형과 같은
 * [millis] `emphasized`)을 그대로 따라간다(끌어서 뒤로의 seek에도 맞는다). 떠날 때는 [atRest] 그대로다. 공유 요소
 * overlay에는 들어오는 쪽(목표) 내용이 그려지므로, push는 상세가 20 → 0(보드 FProductDetail은 칸 안 사진에 모서리가
 * 없다), pop은 카드가 0 → 20으로 바꾸고 떠나는 쪽은 건드리지 않아 모서리가 한 방향으로만 바뀐다.
 */
@Composable
fun photoCorner(atRest: Dp, entering: Dp, millis: Int): Dp {
    val scope = LocalWLStackScope.current ?: return atRest
    val corner by scope.transition.animateDp(
        transitionSpec = { tween(millis, easing = Curve.emphasized) },
        label = "photoCorner",
    ) { if (it == EnterExitState.PreEnter) entering else atRest }
    return corner
}
