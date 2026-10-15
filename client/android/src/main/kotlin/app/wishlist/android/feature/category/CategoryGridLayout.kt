package app.wishlist.android.feature.category

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/**
 * One list's photo layout (D10) as numbers only: the pages (one ITEM-02 answer each), every card's
 * measured photo size, and the laid-out ratios. It lives in the list entry's ViewModelStore
 * ([CategoryGridHolder]), not in `remember`: the list leaves composition while the detail it opened is
 * shown, and coming back must draw the laid-out cards at once — the scroll position (D3) and the card the
 * photo shrinks back into (C5 PR B) — even when the image memory cache no longer has their photos.
 */
class CategoryGridLayout {
    val pages = mutableStateListOf<List<String>>()
    val photos = mutableStateMapOf<String, PhotoPagePlanner.Photo>()
    /** Laid-out ratios as state, so a settled page recomposes the grid (the planner itself is plain). */
    val ratios = mutableStateMapOf<String, Float>()
    private val planner = PhotoPagePlanner()

    val laidOutPages: Int get() = planner.laidOutPages

    /** Each new tail of ids is a page; a list that no longer starts the same way (refresh) starts again. */
    fun onItems(ids: List<String>) {
        val known = pages.flatten()
        if (known.isNotEmpty() && (ids.size < known.size || ids.take(known.size) != known)) {
            pages.clear(); planner.reset(); ratios.clear(); photos.clear()
        }
        val laid = pages.flatten().toSet()
        val fresh = ids.filter { it !in laid }
        if (fresh.isNotEmpty()) pages.add(fresh)
    }

    /** [PhotoPagePlanner.settle] with the measured sizes; a laid-out page publishes its ratios. */
    fun settle(index: Int, cards: List<PhotoPagePlanner.Card>, elapsedMs: Long): Boolean {
        val done = planner.settle(index, cards, photos.toMap(), elapsedMs)
        if (done) cards.forEach { card -> planner.ratioOf(card.id)?.let { if (ratios[card.id] != it) ratios[card.id] = it } }
        return done
    }
}

/** Keeps a list entry's [CategoryGridLayout] for as long as the entry is on the stack. */
class CategoryGridHolder : ViewModel() {
    val layout = CategoryGridLayout()

    companion object {
        fun of(store: ViewModelStoreOwner): CategoryGridLayout =
            ViewModelProvider(store, viewModelFactory { initializer { CategoryGridHolder() } })["category.list.grid", CategoryGridHolder::class.java].layout
    }
}
