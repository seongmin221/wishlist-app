package app.wishlist.shared.repository

import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.model.*

/**
 * B2 custom category API (CAT-01~04), owner-scoped. Errors keep the server code in
 * [app.wishlist.shared.core.ClientError.code] (`CATEGORY_NAME_DUPLICATE`, `CATEGORY_LIMIT_REACHED`,
 * `CATEGORY_CREATE_RATE_LIMITED` + retryAfterSeconds, `CATEGORY_VERSION_CONFLICT` + currentVersion,
 * `CATEGORY_NOT_FOUND`, `INVALID_CATEGORY_INPUT` + fields, `INVALID_CATEGORY_PARENT`,
 * `IDEMPOTENCY_KEY_REUSED`, `CATEGORY_NOT_AVAILABLE`).
 */
interface CategoryRepository {
    /** CAT-01. [parentId] limits the groups to that one G-ID. */
    suspend fun catalog(scope: CategoryScope, parentId: String? = null): ClientResult<CategoryCatalog>

    /** CAT-02 (custom UUID only). */
    suspend fun custom(id: String): ClientResult<CustomCategoryDetail>

    /** CAT-03. The same [idempotencyKey] with the same command replays the first result. */
    suspend fun create(command: CategoryCommand, idempotencyKey: String): ClientResult<CategoryCreated>

    /** CAT-04. Sends only the changed fields of [patch]. */
    suspend fun update(id: String, expectedVersion: Int, patch: CategoryPatch): ClientResult<CustomCategoryDetail>
}

/** B4 ITEM-02, category and purpose scopes. Newest first; [cursor] is a page's next or previous cursor. */
interface ItemListRepository {
    suspend fun byCategory(categoryId: String, cursor: String? = null, limit: Int = DEFAULT_LIMIT): ClientResult<ItemPage>

    /** All ACTIVE candidates, including unnamed items. Default keeps older category-only consumers compatible. */
    suspend fun byPurpose(purposeId: String, cursor: String? = null, limit: Int = DEFAULT_LIMIT): ClientResult<ItemPage> =
        ClientResult.Failure(app.wishlist.shared.core.ClientError(app.wishlist.shared.core.ErrorKind.UNAVAILABLE))

    /**
     * ITEM-02 window around a card's `anchorCursor`: up to [before] newer + the anchor + [after] older
     * (each 0~20). A card that left the scope falls back (see [ItemAnchor]); never sent with cursor/limit.
     */
    suspend fun byCategoryAround(categoryId: String, anchor: String, before: Int = DEFAULT_SPAN, after: Int = DEFAULT_SPAN): ClientResult<ItemPage> =
        ClientResult.Failure(app.wishlist.shared.core.ClientError(app.wishlist.shared.core.ErrorKind.UNAVAILABLE))

    /** Purpose-scope window; same rules as [byCategoryAround]. */
    suspend fun byPurposeAround(purposeId: String, anchor: String, before: Int = DEFAULT_SPAN, after: Int = DEFAULT_SPAN): ClientResult<ItemPage> =
        ClientResult.Failure(app.wishlist.shared.core.ClientError(app.wishlist.shared.core.ErrorKind.UNAVAILABLE))

    companion object {
        const val DEFAULT_LIMIT = 40
        const val DEFAULT_SPAN = 20
    }
}
