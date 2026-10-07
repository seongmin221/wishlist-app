package app.wishlist.android.navigation

/** Features encode routes using bundle-compatible string tokens; navigation owns root routes. */
interface WLRouteCodec {
    fun encode(route: WLRoute): List<String>?
    fun decode(tokens: List<String>): WLRoute?
}
