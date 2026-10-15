package app.wishlist.android.feature.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.wishlist.android.navigation.WLPushStyle
import app.wishlist.android.navigation.WLRoute
import app.wishlist.shared.model.CategoryKind
import app.wishlist.shared.presentation.CategoryListHint
import app.wishlist.shared.presentation.Presenter
import java.util.Base64

/**
 * One category's item list (FCategoryList / ListCustom / ListCustomEmpty): a slide push from a chip,
 * the tab bar stays (both screens show it, so it does not move), account-scoped (closed on leaving
 * the account). [hint] draws the header at once and after a process restore.
 */
internal data class CategoryListRoute(val hint: CategoryListHint) : WLRoute {
    override val showsTabBar = true
    override val pushStyle = WLPushStyle.Slide
    override val accountScoped = true

    companion object {
        private const val TOKEN = "category"

        /** `category/<id>/<b64 name>/<parentId>/<b64 parent name>/<kind>`: names may hold `/ ? # %`. */
        fun encode(route: CategoryListRoute): List<String> = with(route.hint) {
            listOf(TOKEN, categoryId, b64(name), parentId, b64(parentName), kind.name)
        }

        /** null on a wrong shape, bad base64, an empty field or an unknown kind. */
        fun decode(tokens: List<String>): CategoryListRoute? {
            if (tokens.size != 6 || tokens[0] != TOKEN) return null
            val name = unb64(tokens[2]) ?: return null
            val parentName = unb64(tokens[4]) ?: return null
            val kind = CategoryKind.entries.firstOrNull { it.name == tokens[5] } ?: return null
            if (tokens[1].isEmpty() || tokens[3].isEmpty() || name.isEmpty()) return null
            return CategoryListRoute(CategoryListHint(tokens[1], name, tokens[3], parentName, kind))
        }

        private fun b64(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))
        private fun unb64(token: String): String? = try {
            String(Base64.getUrlDecoder().decode(token), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/**
 * "A custom category changed" (a list screen's edit was saved): the tab's first screen refreshes its
 * chips. The two screens own separate Presenters in separate entry stores, so the list tells the home
 * through this process-wide counter instead of reaching its owner.
 */
object CategoryChanges {
    private val mutable = kotlinx.coroutines.flow.MutableStateFlow(0)
    val revision: kotlinx.coroutines.flow.StateFlow<Int> = mutable
    fun changed() { mutable.value++ }
}

/**
 * Lifetime owner of one shared Presenter (no UI): kept across configuration changes, closed in
 * [onCleared] when the screen's entry ViewModelStore is cleared (pop, account leave). The category
 * Presenters' constructors are shared-internal, so the owner takes the runtime-made instance.
 */
class CategoryPresenterOwner<P : Presenter>(val presenter: P) : ViewModel() {
    private var started = false
    private var seenRevision = CategoryChanges.revision.value

    /** Records [revision]; true when it is newer than the one this owner saw (the screen should refresh). */
    fun markSeen(revision: Int): Boolean {
        if (revision == seenRevision) return false
        seenRevision = revision
        return true
    }

    /** Runs [block] on the first call only (the screen's first composition, not a recomposition after rotation). */
    fun once(block: (P) -> Unit) {
        if (started) return
        started = true
        block(presenter)
    }

    override fun onCleared() {
        presenter.close()
    }

    companion object {
        /** The owner of [key] in [store], made by [make] the first time. */
        @Suppress("UNCHECKED_CAST")
        fun <P : Presenter> of(store: ViewModelStoreOwner, key: String, make: () -> P): CategoryPresenterOwner<P> =
            ViewModelProvider(store, viewModelFactory { initializer { CategoryPresenterOwner(make()) } })[key, CategoryPresenterOwner::class.java]
                as CategoryPresenterOwner<P>
    }
}
