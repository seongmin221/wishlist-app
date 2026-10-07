package app.wishlist.shared.model

/** Flat B2 catalog: G-ID groups, C-ID public leaves, and UUID custom leaves. Kind stays raw. */
data class Category(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val kind: String? = null,
    val version: Int? = null,
) {
    init {
        require(kind != "CUSTOM" || (version != null && version > 0)) { "Custom category version must be positive" }
    }
}
