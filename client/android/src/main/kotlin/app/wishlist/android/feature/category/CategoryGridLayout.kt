package app.wishlist.android.feature.category

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/**
 * One list's photo layout (D10) as numbers only: the current [PhotoGridLayout] (pages and laid-out ratios)
 * and every card's measured photo size. It lives in the list entry's ViewModelStore ([CategoryGridHolder]),
 * not in `remember`: the list leaves composition while the detail it opened is shown, and coming back must
 * draw the laid-out cards at once — the scroll position (D3, C7 anchor) and the card the photo shrinks back
 * into (C5 PR B) — even when the image memory cache no longer has their photos.
 */
internal class CategoryGridState {
    val sizes = PhotoMeasurements()
    /** A restart replaces the whole layout (a job of the old one can no longer touch the one on screen). */
    var layout by mutableStateOf(PhotoGridLayout())

    /**
     * Each new tail of ids is a page; a list that no longer starts the same way (or whose photo changed) is a
     * restart. Composed again with the same items (back from the detail), nothing changes.
     */
    fun onItems(ids: List<String>, photoUrls: Map<String, String?>) {
        if (restartsLayout(layout.pages.flatten(), layout.photos, ids, photoUrls)) layout = PhotoGridLayout()
        val current = layout
        val laid = current.pages.flatten().toSet()
        val fresh = ids.filter { it !in laid }
        if (fresh.isNotEmpty()) current.pages.add(fresh)
        fresh.forEach { current.photos[it] = photoUrls[it] }
    }
}

/** Keeps a list entry's [CategoryGridState] for as long as the entry is on the stack. */
internal class CategoryGridHolder : ViewModel() {
    val grid = CategoryGridState()

    companion object {
        fun of(store: ViewModelStoreOwner): CategoryGridState =
            ViewModelProvider(store, viewModelFactory { initializer { CategoryGridHolder() } })["category.list.grid", CategoryGridHolder::class.java].grid
    }
}
