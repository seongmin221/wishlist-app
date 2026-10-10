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
import androidx.compose.runtime.LaunchedEffect
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
import app.wishlist.android.navigation.WLEntryViewModelStores
import app.wishlist.android.navigation.WLNavHost
import app.wishlist.android.navigation.rememberWLNavigator
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 앱 루트: 테마 → overlay(시트·확인창·메뉴) → 탭 셸, 그 위에 첫 실행 로그인 안내(전체 화면, 탭 바 없음).
 * 안내는 복원이 끝난 뒤 로그인 전이고 아직 보지 않았을 때만 뜨고(`showFirstRunLogin`), 로그인하거나 "나중에 하기"를
 * 누르면 사라진다. 뜨는 동안 아래 탭 셸은 입력·접근성에서 가려진다.
 *
 * 계정을 떠나면(로그아웃·다른 계정, [shouldDropAccountScoped]) 모든 탭의 계정 범위 화면을 전환 없이 닫고 그 화면들의
 * ViewModelStore를 바로 지운다. 비교는 이 composition 동안의 메모리 값이다(복원 직후 첫 값은 기준값일 뿐 떠남이 아니다).
 */
@Composable
internal fun WishlistApp(account: AccountPresenterOwner, home: HomePresenterOwner, entryStores: WLEntryViewModelStores) {
    WLTheme {
        val navigator = rememberWLNavigator(AppRouteCodec)
        LaunchedEffect(account, navigator, entryStores) {
            var previous: String? = null
            account.state.map { it.account?.accountId }.distinctUntilChanged().collect { next ->
                if (shouldDropAccountScoped(previous, next)) navigator.dropAccountScoped().forEach(entryStores::clear)
                previous = next
            }
        }
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
                        WLNavHost(navigator = navigator, entryStores = entryStores) { route, sourceKey -> AppRoute(route, sourceKey) }
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

/** 계정을 떠났는지: 로그인한 계정이 있었고 그 값이 바뀌었을 때(로그아웃 또는 다른 계정). 로그인(null → 계정)은 떠남이 아니다. */
internal fun shouldDropAccountScoped(previous: String?, next: String?): Boolean = previous != null && previous != next
