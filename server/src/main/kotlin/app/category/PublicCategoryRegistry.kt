package app.category

import app.ai.TaxonomyCatalog

data class PublicCategory(val id: String, val name: String, val parentId: String, val displayOrder: Int)
data class PublicCategoryGroup(val id: String, val name: String, val displayOrder: Int, val categories: List<PublicCategory>)

class PublicCategoryRegistry(catalog: TaxonomyCatalog) {
    val taxonomyVersion = catalog.version
    val groups: List<PublicCategoryGroup> = catalog.groups.mapIndexed { order, group ->
        val id = requireNotNull(GROUP_IDS[group.name]) { "Taxonomy group requires a stable registry ID" }
        PublicCategoryGroup(id, group.name, order, group.categories.mapIndexed { leafOrder, category ->
            PublicCategory(category.id, category.name, id, leafOrder)
        })
    }

    fun parent(id: String): PublicCategoryGroup? = groups.firstOrNull { it.id == id }

    companion object {
        // Explicit IDs: adding or reordering taxonomy groups must never renumber existing parents.
        private val GROUP_IDS = mapOf(
            "패션·잡화" to "G001", "뷰티·퍼스널케어" to "G002", "디지털·IT" to "G003",
            "가구·인테리어" to "G004", "생활·주방·가전" to "G005", "스포츠·아웃도어·여행" to "G006",
            "취미·문화·컬렉터블" to "G007", "유아·키즈" to "G008", "반려동물" to "G009",
            "자동차·모빌리티" to "G010", "건강·웰빙" to "G011",
        )
    }
}
