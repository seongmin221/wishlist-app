package app.http

import app.category.CategoryChange
import app.category.CategoryInput
import app.category.CategoryInputPolicy
import kotlinx.serialization.json.*

data class CategoryCreateRequest(val parentId: String, val input: CategoryInput)

data class CategoryPatchRequest(
    val expectedVersion: Int,
    val name: String?,
    val description: CategoryChange<String?>,
    val examples: CategoryChange<List<String>>,
)

sealed interface CategoryRequestParseResult<out T> {
    data class Valid<T>(val request: T) : CategoryRequestParseResult<T>
    data class Invalid(val code: String = "INVALID_CATEGORY_INPUT", val fields: Set<String> = emptySet()) : CategoryRequestParseResult<Nothing>
}

fun parseCategoryCreateRequest(raw: String): CategoryRequestParseResult<CategoryCreateRequest> {
    val body = parseJsonObject(raw) ?: return CategoryRequestParseResult.Invalid()
    if (body.keys.any { it !in setOf("parentId", "name", "description", "examples") }) return CategoryRequestParseResult.Invalid()
    val parent = body["parentId"].strictString() ?: return CategoryRequestParseResult.Invalid(fields = setOf("parentId"))
    val name = body["name"].strictString() ?: return CategoryRequestParseResult.Invalid(fields = setOf("name"))
    if (!body.optionalStringValid("description")) return CategoryRequestParseResult.Invalid(fields = setOf("description"))
    val examples = body.examples() ?: return CategoryRequestParseResult.Invalid(fields = setOf("examples"))
    val input = CategoryInput(name, body["description"].strictString(), examples)
    val invalid = CategoryInputPolicy.validate(input)
    return if (invalid.isEmpty()) CategoryRequestParseResult.Valid(CategoryCreateRequest(parent, input))
        else CategoryRequestParseResult.Invalid(fields = invalid)
}

fun parseCategoryPatchRequest(raw: String): CategoryRequestParseResult<CategoryPatchRequest> {
    val body = parseJsonObject(raw) ?: return CategoryRequestParseResult.Invalid()
    if ("parentId" in body) return CategoryRequestParseResult.Invalid("CATEGORY_PARENT_IMMUTABLE", setOf("parentId"))
    if (body.keys.any { it !in setOf("expectedVersion", "name", "description", "examples") } ||
        body.keys.none { it in setOf("name", "description", "examples") }) return CategoryRequestParseResult.Invalid()
    val expected = body.positiveVersion("expectedVersion")
        ?: return CategoryRequestParseResult.Invalid(fields = setOf("expectedVersion"))
    val name = body["name"].strictString()
    if ("name" in body && name == null) return CategoryRequestParseResult.Invalid(fields = setOf("name"))
    if (!body.optionalStringValid("description")) return CategoryRequestParseResult.Invalid(fields = setOf("description"))
    val examples = body.examples() ?: return CategoryRequestParseResult.Invalid(fields = setOf("examples"))
    val description = body["description"].strictString()
    val invalid = CategoryInputPolicy.validate(CategoryInput(name ?: "unchanged", description, examples))
    if (invalid.isNotEmpty()) return CategoryRequestParseResult.Invalid(fields = invalid)
    return CategoryRequestParseResult.Valid(CategoryPatchRequest(expected, name,
        if ("description" in body) CategoryChange.Set(description) else CategoryChange.Keep,
        if ("examples" in body) CategoryChange.Set(examples) else CategoryChange.Keep,
    ))
}

private fun JsonObject.examples(): List<String>? {
    val value = this["examples"] ?: return emptyList()
    if (value == JsonNull) return emptyList()
    val array = value as? JsonArray ?: return null
    val strings = array.map { it.strictString() ?: return null }
    return strings
}
