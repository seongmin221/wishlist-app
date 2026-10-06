package app.wishlist.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import app.wishlist.android.navigation.LocalWLNavigator
import app.wishlist.android.navigation.LocalWLSurfaceRegistry
import app.wishlist.android.navigation.WLSurfaceRegistry
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
        val registry = remember { WLSurfaceRegistry() }
        CompositionLocalProvider(LocalWLNavigator provides navigator, LocalWLSurfaceRegistry provides registry) {
            OverlayHost(rememberOverlayHostState()) {
                WLNavHost(navigator = navigator) { route, sourceKey -> AppRoute(route, sourceKey) }
            }
        }
    }
}
