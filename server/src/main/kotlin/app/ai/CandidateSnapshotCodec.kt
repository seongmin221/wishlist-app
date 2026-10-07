package app.ai

import kotlinx.serialization.json.*

/** One versioned JSON boundary for both snapshot reuse and final result validation. */
internal object CandidateSnapshotCodec {
    fun decode(raw: String): CandidateSnapshot? = try {
        val root = Json.parseToJsonElement(raw) as? JsonObject ?: return null
        val version = root["schema_version"]?.let { integer(it) } ?: 1
        require(version in 1..2)
        val ids = strings(root["categories"]).toSet()
        val purposes = strings(root["purposes"]).toSet()
        require(ids.isNotEmpty() && purposes.size <= 10)
        val custom = if (version == 2) {
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
            ids, purposes, labels(root["category_labels"]), labels(root["purpose_labels"]),
            root["owner_id"]?.let(::string), custom, version,
        )
        require(snapshot.categoryLabels.keys.all { it in ids })
        require(snapshot.purposeLabels.keys.all { it in purposes })
        snapshot
    } catch (_: IllegalArgumentException) { null }

    fun encode(snapshot: CandidateSnapshot): String = JsonObject(mapOf(
        "schema_version" to JsonPrimitive(snapshot.schemaVersion),
        "owner_id" to JsonPrimitive(requireNotNull(snapshot.ownerId)),
        "custom_categories" to JsonObject(snapshot.customCategories.mapValues { (_, row) -> JsonObject(mapOf(
            "version" to JsonPrimitive(row.version), "name" to JsonPrimitive(row.name),
            "parent_id" to JsonPrimitive(row.parentId),
            "description" to (row.description?.let(::JsonPrimitive) ?: JsonNull),
            "examples" to JsonArray(row.examples.map(::JsonPrimitive)),
        )) }),
        "categories" to JsonArray(snapshot.categoryIds.sorted().map(::JsonPrimitive)),
        "purposes" to JsonArray(snapshot.purposeIds.sorted().map(::JsonPrimitive)),
        "category_labels" to JsonObject(snapshot.categoryLabels.mapValues { JsonPrimitive(it.value) }),
        "purpose_labels" to JsonObject(snapshot.purposeLabels.mapValues { JsonPrimitive(it.value) }),
    )).toString()

    private fun string(value: JsonElement?): String =
        requireNotNull((value as? JsonPrimitive)?.takeIf { it.isString }?.content)

    private fun integer(value: JsonElement?): Int =
        requireNotNull((value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull).also { require(it > 0) }

    private fun strings(value: JsonElement?): List<String> =
        requireNotNull(value as? JsonArray).map(::string)

    private fun labels(value: JsonElement?): Map<String, String> =
        if (value == null) emptyMap() else requireNotNull(value as? JsonObject).mapValues { string(it.value) }
}
