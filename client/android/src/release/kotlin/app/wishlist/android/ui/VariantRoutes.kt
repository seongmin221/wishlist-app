package app.wishlist.android.ui

import androidx.compose.runtime.Composable
import app.wishlist.android.navigation.*

internal object VariantRoutes : WLRouteCodec {
    override fun encode(route: WLRoute): List<String>? = null
    override fun decode(tokens: List<String>): WLRoute? = null
    @Composable
    fun Content(route: WLRoute, sourceKey: String?): Boolean = false
}
