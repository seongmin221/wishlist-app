package app.wishlist.android.feature.demo

import app.wishlist.android.navigation.WLRoute
import app.wishlist.android.navigation.WLPushStyle

internal object DemoRoute {
    data class Detail(val id: String, val hasPhoto: Boolean) : WLRoute {
        override val showsTabBar = false
        override val pushStyle get() = if (hasPhoto) WLPushStyle.Photo else WLPushStyle.Surface
    }
}
