package app.wishlist.shared.core

/** Callback boundary implemented by platform authentication providers. */
interface TokenCallback {
    fun complete(token: String?, errorCode: String?)
}

interface TokenRequest {
    fun cancel()
}

interface PlatformTokenSource {
    fun fetchToken(forceRefresh: Boolean, completion: TokenCallback): TokenRequest
}
