package app.ai

import app.common.parseCanonicalUuid
import kotlinx.serialization.json.*

/** One versioned JSON boundary for both snapshot reuse and final result validation. */
internal object CandidateSnapshotCodec {
    fun decode(raw: String): CandidateSnapshot? = try {
        val root = Json.parseToJsonElement(raw) as? JsonObject ?: return null
        val version = root["schema_version"]?.let { integer(it) } ?: 1
        require(version in 1..3)
        val ids = strings(root["categories"]).toSet()
        val purposeRows = if (version == 3) {
            require(root["purpose_labels"] == null)
            requireNotNull(root["purposes"] as? JsonArray).map { value ->
                val row = requireNotNull(value as? JsonObject)
                PurposeCandidate(canonicalUuid(string(row["id"])), string(row["name"]),
                    row["description"]?.takeUnless { it == JsonNull }?.let(::string), strings(row["item_names"]))
            }
        } else emptyList()
        val purposes = if (version == 3) purposeRows.map { it.id }.toCollection(LinkedHashSet()) else strings(root["purposes"]).toSet()
        require(ids.isNotEmpty() && purposes.size <= 10 && (version != 3 || purposes.size == purposeRows.size))
        val custom = if (version >= 2) {
            requireNotNull(root["custom_categories"] as? JsonObject).mapValues { (_, value) ->
                val row = requireNotNull(value as? JsonObject)
                CustomCategoryCandidate(
                    integer(row["version"]), string(row["name"]), string(row["parent_id"]),
                    row["description"]?.takeUnless { it == JsonNull }?.let(::string), strings(row["examples"]),
                )
            }
        } else {
            require(root["custom_categories"] == null)
            emptyMap()
        }
        val snapshot = CandidateSnapshot(
            ids, purposes, labels(root["category_labels"]), if (version == 3) emptyMap() else labels(root["purpose_labels"]),
            root["owner_id"]?.let(::string), custom, version, purposeRows,
        )
        require(snapshot.categoryLabels.keys.all { it in ids })
        require(snapshot.purposeLabels.keys.all { it in purposes })
        snapshot
    } catch (_: IllegalArgumentException) { null }

    fun encode(snapshot: CandidateSnapshot): String {
        val v3 = snapshot.schemaVersion == 3
        if (v3) require(snapshot.purposeIds.toList() == snapshot.purposeCandidates.map { it.id }) { "v3 purposes must follow candidate order" }
        return JsonObject(buildMap {
            put("schema_version", JsonPrimitive(snapshot.schemaVersion))
            put("owner_id", JsonPrimitive(requireNotNull(snapshot.ownerId)))
            put("custom_categories", JsonObject(snapshot.customCategories.mapValues { (_, row) -> JsonObject(mapOf(
                "version" to JsonPrimitive(row.version), "name" to JsonPrimitive(row.name),
                "parent_id" to JsonPrimitive(row.parentId),
                "description" to (row.description?.let(::JsonPrimitive) ?: JsonNull),
                "examples" to JsonArray(row.examples.map(::JsonPrimitive)),
            )) }))
            put("categories", JsonArray(snapshot.categoryIds.sorted().map(::JsonPrimitive)))
            put("category_labels", JsonObject(snapshot.categoryLabels.mapValues { JsonPrimitive(it.value) }))
            if (v3) put("purposes", JsonArray(snapshot.purposeCandidates.map { row -> JsonObject(mapOf(
                "id" to JsonPrimitive(row.id), "name" to JsonPrimitive(row.name),
                "description" to (row.description?.let(::JsonPrimitive) ?: JsonNull),
                "item_names" to JsonArray(row.itemNames.map(::JsonPrimitive)),
            )) }))
            else {
                put("purposes", JsonArray(snapshot.purposeIds.sorted().map(::JsonPrimitive)))
                put("purpose_labels", JsonObject(snapshot.purposeLabels.mapValues { JsonPrimitive(it.value) }))
            }
        }).toString()
    }

    private fun canonicalUuid(value: String): String = requireNotNull(parseCanonicalUuid(value)).toString()

    private fun string(value: JsonElement?): String =
        requireNotNull((value as? JsonPrimitive)?.takeIf { it.isString }?.content)

    private fun integer(value: JsonElement?): Int =
        requireNotNull((value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull).also { require(it > 0) }

    private fun strings(value: JsonElement?): List<String> =
        requireNotNull(value as? JsonArray).map(::string)

    private fun labels(value: JsonElement?): Map<String, String> =
        if (value == null) emptyMap() else requireNotNull(value as? JsonObject).mapValues { string(it.value) }
}
