package app.wishlist.android.platform

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView

/**
 * 설정 "웹뷰 데이터 삭제"(C3-D5 h): the platform's default WebView stores as a whole — cookies
 * (store sign-ins), Web Storage, and the HTTP cache. Main thread only. Returns false when the
 * WebView provider is missing or failed to load (nothing to clear, and the row stays as it was).
 */
object WebViewDataCleaner {
    fun clear(context: Context): Boolean = try {
        val cookies = CookieManager.getInstance()
        // Removal is asynchronous; persist the empty jar once it finished.
        cookies.removeAllCookies { cookies.flush() }
        WebStorage.getInstance().deleteAllData()
        WebView(context).apply {
            clearCache(true)
            destroy()
        }
        true
    } catch (e: RuntimeException) {
        // AndroidRuntimeException / MissingWebViewPackageException when no WebView provider exists.
        false
    }
}
