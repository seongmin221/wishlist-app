package app.http

import app.category.CategoryChange
import app.purpose.*
import kotlinx.serialization.json.JsonObject

sealed interface PurposeParseResult<out T> {
    data class Valid<T>(val request: T) : PurposeParseResult<T>
    data class Invalid(val fields: Set<String> = emptySet()) : PurposeParseResult<Nothing>
}

data class PurposePatchRequest(val expectedVersion: Int, val changes: PurposeChanges)

private val EDITABLE = setOf("name", "description", "colorKey", "iconKey")

fun parsePurposeCreateRequest(raw: String): PurposeParseResult<PurposeInput> {
    val body = parseJsonObject(raw) ?: return PurposeParseResult.Invalid()
    if (body.keys.any { it !in EDITABLE }) return PurposeParseResult.Invalid()
    val name = body["name"].strictString()
    val color = body["colorKey"].strictString()?.let(PurposeStyle::color)
    val icon = body["iconKey"].strictString()?.let(PurposeStyle::icon)
    val description = body["description"].strictString()
    val fields = buildSet {
        addAll(PurposeInputPolicy.validate(name, description))
        if (!body.optionalStringValid("description")) add("description")
        if (color == null) add("colorKey")
        if (icon == null) add("iconKey")
    }
    return if (fields.isEmpty()) PurposeParseResult.Valid(PurposeInput(name!!, description, color!!, icon!!)) else PurposeParseResult.Invalid(fields)
}

fun parsePurposePatchRequest(raw: String): PurposeParseResult<PurposePatchRequest> {
    val body = parseJsonObject(raw) ?: return PurposeParseResult.Invalid()
    if (body.keys.any { it != "expectedVersion" && it !in EDITABLE } || body.keys.none { it in EDITABLE }) return PurposeParseResult.Invalid()
    val expected = body.positiveVersion("expectedVersion") ?: return PurposeParseResult.Invalid(setOf("expectedVersion"))
    val name = body["name"].strictString()
    val color = body["colorKey"].strictString()?.let(PurposeStyle::color)
    val icon = body["iconKey"].strictString()?.let(PurposeStyle::icon)
    val description = body["description"].strictString()
    val fields = buildSet {
        if ("name" in body) addAll(PurposeInputPolicy.validate(name, null))
        if (!body.optionalStringValid("description") || description != null && PurposeInputPolicy.validate("unchanged", description).isNotEmpty()) add("description")
        if ("colorKey" in body && color == null) add("colorKey")
        if ("iconKey" in body && icon == null) add("iconKey")
    }
    if (fields.isNotEmpty()) return PurposeParseResult.Invalid(fields)
    return PurposeParseResult.Valid(PurposePatchRequest(expected, PurposeChanges(
        name, if ("description" in body) CategoryChange.Set(description) else CategoryChange.Keep, color, icon,
    )))
}
