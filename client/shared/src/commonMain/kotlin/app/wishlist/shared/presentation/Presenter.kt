package app.wishlist.shared.presentation

/**
 * Lifetime contract shared by every Presenter. A platform owner (Android `ViewModel.onCleared`,
 * iOS `@MainActor` owner `close()`/`deinit`) calls [close] exactly when its screen goes away.
 *
 * - Intents may be called from the UI thread; repository work never runs there.
 * - `state` is a read-only, thread-safe StateFlow; owners hop to the main thread to render it.
 * - [close] is idempotent: it cancels in-flight work, and every later intent is ignored.
 */
interface Presenter {
    fun close()
}
