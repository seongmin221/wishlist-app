package app.wishlist.shared.model

/** CAT-01 scope: SELECT is the whole taxonomy plus every custom; BROWSE only what the category tab shows. */
enum class CategoryScope { SELECT, BROWSE }

/** Wire `kind`. An unknown value is UNKNOWN (shown like PUBLIC: no ⋯); [CategoryEntry.rawKind] keeps the original. */
enum class CategoryKind { PUBLIC, CUSTOM, UNKNOWN }

/** A selectable leaf: public C-ID or custom UUID (canonical lowercase). */
data class CategoryEntry(
    val id: String,
    val name: String,
    val parentId: String,
    val kind: CategoryKind,
    val rawKind: String,
    val displayOrder: Int,
    val itemCount: Int,
)

/** A public top-level group (G-ID). Not selectable; [itemCount] is the sum of the returned leaves. */
data class CategoryGroup(
    val id: String,
    val name: String,
    val displayOrder: Int,
    val itemCount: Int,
    val categories: List<CategoryEntry>,
)

/** CAT-01 response. [customUsedCount] counts every live custom of the owner regardless of the parent filter. */
data class CategoryCatalog(
    val scope: CategoryScope,
    val taxonomyVersion: String,
    val groups: List<CategoryGroup>,
    val customUsedCount: Int,
    val customLimit: Int,
)

/** CAT-02 / CAT-04 representation of a custom category. */
data class CustomCategoryDetail(
    val id: String,
    val name: String,
    val parentId: String,
    val description: String?,
    val examples: List<String>,
    val itemCount: Int,
    val version: Int,
)

/** CAT-03 result (first 201 and replay 200 are the same shape). */
data class CategoryCreated(
    val category: CustomCategoryDetail,
    val customUsedCount: Int,
    val customLimit: Int,
)

/** CAT-03 body. Payload identity for idempotency is these four values (null description = none). */
data class CategoryCommand(
    val parentId: String,
    val name: String,
    val description: String?,
    val examples: List<String>,
)

/** CAT-04 description change: keep it, or set it (null clears). */
sealed interface DescriptionChange {
    data object Keep : DescriptionChange
    data class Set(val value: String?) : DescriptionChange
}

/** CAT-04 body without expectedVersion. Null name/examples = keep; examples empty = clear. */
data class CategoryPatch(
    val name: String? = null,
    val description: DescriptionChange = DescriptionChange.Keep,
    val examples: List<String>? = null,
) {
    val isEmpty: Boolean get() = name == null && description == DescriptionChange.Keep && examples == null
}

/** One ITEM-02 page. [nextCursor] is opaque and null on the last page. */
data class ItemPage(
    val items: List<WishlistItem>,
    val totalCount: Int,
    val nextCursor: String?,
)
