package app.wishlist

import app.category.CategoryRef
import java.time.Instant
import java.util.UUID

sealed interface ReadScope {
    data class Category(val ref: CategoryRef) : ReadScope
    data class Purpose(val id: UUID) : ReadScope
    data object PurposeUnassigned : ReadScope
    data class Action(val group: HomeActionGroup) : ReadScope
}

data class ReadPosition(val createdAt: Instant, val id: UUID)
enum class ReadDirection { OLDER, NEWER }
sealed interface ReadWindow {
    data class Page(val limit: Int, val boundary: ReadPosition? = null, val direction: ReadDirection = ReadDirection.OLDER, val boundaryInclusive: Boolean = false) : ReadWindow
    data class Anchor(val position: ReadPosition, val before: Int = 20, val after: Int = 20) : ReadWindow
}
data class ReadQuery(val scope: ReadScope, val window: ReadWindow)
data class ReadPage(
    val items: List<WishlistItem>, val totalCount: Long, val previous: ReadPosition?, val next: ReadPosition?,
    val requestedAnchorItemId: UUID?, val resolvedAnchorItemId: UUID?, val anchorResolved: Boolean?,
    val previousInclusive: Boolean = false, val nextInclusive: Boolean = false,
)
sealed interface ReadResult {
    data class Success(val page: ReadPage) : ReadResult
    data object CategoryNotFound : ReadResult
    data object PurposeNotFound : ReadResult
}
