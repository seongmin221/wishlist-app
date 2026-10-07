package app.wishlist.shared.repository

import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.model.Category
import app.wishlist.shared.model.Purpose
import app.wishlist.shared.model.WishlistItem

/** C2 seed queries. This interface does not specify CAT/PUR/ITEM list wire projections or paging. */
interface CatalogRepository {
    suspend fun categories(): ClientResult<List<Category>>
    suspend fun purposes(): ClientResult<List<Purpose>>
    /** Optional IDs restrict actual membership; board display counts are never query inputs. */
    suspend fun items(categoryId: String?, purposeId: String?): ClientResult<List<WishlistItem>>
}
