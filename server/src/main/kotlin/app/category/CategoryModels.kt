package app.category

import java.util.UUID
import java.time.Instant

enum class CategoryScope { SELECT, BROWSE }
data class CustomCategory(val id: UUID, val parentId: String, val input: CategoryInput, val version: Int, val itemCount: Long,
    val createdAt: Instant, val aiEligible: Boolean, val displayOrder: Int)
data class CategoryEntry(val id: String, val name: String, val parentId: String, val kind: String, val displayOrder: Int, val itemCount: Long)
data class CategoryGroup(val id: String, val name: String, val displayOrder: Int, val itemCount: Long, val categories: List<CategoryEntry>)
data class CategoryList(val scope: CategoryScope, val taxonomyVersion: String, val groups: List<CategoryGroup>, val customUsedCount: Int)
data class CategoryCreation(val category: CustomCategory, val customUsedCount: Int, val replayed: Boolean)
sealed interface CategoryChange<out T> {
    data object Keep : CategoryChange<Nothing>
    data class Set<T>(val value: T) : CategoryChange<T>
}
data class CategoryChanges(val name: String? = null, val description: CategoryChange<String?> = CategoryChange.Keep,
    val examples: CategoryChange<List<String>> = CategoryChange.Keep)
class CategoryException(val code: String, val fields: Set<String> = emptySet(), val currentVersion: Int? = null,
    val retryAfterSeconds: Int? = null) : RuntimeException(code)
