package app.wishlist.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import app.wishlist.android.designsystem.WLTheme
import app.wishlist.android.designsystem.WishlistTokens
import app.wishlist.android.designsystem.overlay.OverlayHost
import app.wishlist.android.designsystem.overlay.rememberOverlayHostState
import app.wishlist.android.designsystem.overlay.wlAccessibilityCovered
import app.wishlist.android.feature.home.HomePresenterOwner
import app.wishlist.android.feature.home.LocalHomeOwner
import app.wishlist.android.feature.login.LoginMode
import app.wishlist.android.feature.login.LoginScreen
import app.wishlist.android.feature.session.AccountPresenterOwner
import app.wishlist.android.feature.session.LocalAccountOwner
import app.wishlist.android.navigation.LocalWLNavigator
import app.wishlist.android.navigation.WLNavHost
import app.wishlist.android.navigation.rememberWLNavigator

/**
 * 앱 루트: 테마 → overlay(시트·확인창·메뉴) → 탭 셸, 그 위에 첫 실행 로그인 안내(전체 화면, 탭 바 없음).
 * 안내는 복원이 끝난 뒤 로그인 전이고 아직 보지 않았을 때만 뜨고(`showFirstRunLogin`), 로그인하거나 "나중에 하기"를
 * 누르면 사라진다. 뜨는 동안 아래 탭 셸은 입력·접근성에서 가려진다.
 */
@Composable
fun WishlistApp(account: AccountPresenterOwner, home: HomePresenterOwner) {
    WLTheme {
        val navigator = rememberWLNavigator(AppRouteCodec)
        val accountState by account.state.collectAsState()
        val firstRun = accountState.showFirstRunLogin
        CompositionLocalProvider(
            LocalWLNavigator provides navigator,
            LocalAccountOwner provides account,
            LocalHomeOwner provides home,
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().wlAccessibilityCovered(firstRun)) {
                    OverlayHost(rememberOverlayHostState()) {
                        WLNavHost(navigator = navigator) { route, sourceKey -> AppRoute(route, sourceKey) }
                    }
                }
                // 처음 그릴 때부터 떠 있다(나타남 모션 없음). 사라짐은 구현 기본값: opacity 260 `accelerate`.
                AnimatedVisibility(
                    visible = firstRun,
                    enter = EnterTransition.None,
                    exit = fadeOut(tween(WishlistTokens.Motion.sheetClose, easing = WishlistTokens.Curve.accelerate)),
                ) {
                    LoginScreen(LoginMode.FirstRun)
                }
            }
        }
    }
}
