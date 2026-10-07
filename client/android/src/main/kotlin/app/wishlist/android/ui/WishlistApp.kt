package app.wishlist.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import app.wishlist.android.navigation.LocalWLNavigator
import app.wishlist.android.navigation.rememberWLNavigator
import app.wishlist.android.designsystem.WLTheme
import app.wishlist.android.designsystem.overlay.OverlayHost
import app.wishlist.android.designsystem.overlay.rememberOverlayHostState
import app.wishlist.android.navigation.WLNavHost

/** 앱 루트: 테마 → overlay(시트·확인창·메뉴) → 탭 셸. */
@Composable
fun WishlistApp() {
    WLTheme {
        val navigator = rememberWLNavigator(AppRouteCodec)
        CompositionLocalProvider(LocalWLNavigator provides navigator) {
            OverlayHost(rememberOverlayHostState()) {
                WLNavHost(navigator = navigator) { route, sourceKey -> AppRoute(route, sourceKey) }
            }
        }
    }
}
