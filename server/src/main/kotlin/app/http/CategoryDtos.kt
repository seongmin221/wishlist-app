package app.http

import app.category.*
import kotlinx.serialization.Serializable

@Serializable data class CustomCategoryDto(val id: String, val name: String, val parentId: String, val description: String?, val examples: List<String>, val itemCount: Long, val version: Int)
@Serializable data class CategoryCreationDto(val category: CustomCategoryDto, val customUsedCount: Int, val customLimit: Int = 20)
@Serializable data class CategoryListDto(val scope: CategoryScope, val taxonomyVersion: String, val groups: List<CategoryGroupDto>, val customUsedCount: Int, val customLimit: Int = 20)
@Serializable data class CategoryGroupDto(val id: String, val name: String, val displayOrder: Int, val itemCount: Long, val categories: List<CategoryEntryDto>)
@Serializable data class CategoryEntryDto(val id: String, val name: String, val parentId: String, val kind: String, val displayOrder: Int, val itemCount: Long)

internal fun CustomCategory.toDto() = CustomCategoryDto(id.toString(), input.name, parentId, input.description, input.examples, itemCount, version)
internal fun CategoryList.toDto() = CategoryListDto(scope, taxonomyVersion, groups.map { group ->
    CategoryGroupDto(group.id, group.name, group.displayOrder, group.itemCount, group.categories.map {
        CategoryEntryDto(it.id, it.name, it.parentId, it.kind, it.displayOrder, it.itemCount)
    })
}, customUsedCount)
