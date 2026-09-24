package app.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class CandidateSnapshot(
    val categoryIds: Set<String>,
    val purposeIds: Set<String>,
    val categoryLabels: Map<String,String> = emptyMap(),
    val purposeLabels: Map<String,String> = emptyMap(),
)

sealed interface ClassificationResult {
    data class Assigned(val categoryId: String, val purposeId: String?) : ClassificationResult
    data object Abstained : ClassificationResult
    data class Unusable(val reason: String) : ClassificationResult
    data object Retryable : ClassificationResult
    data class Terminal(val reason: String) : ClassificationResult
}

object ClassificationSchema {
    fun validate(raw: String, candidates: CandidateSnapshot): ClassificationResult = try {
        val value = Json.parseToJsonElement(raw).jsonObject
        fun field(name: String): String? = value[name]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
        val categoryStatus = field("category_status")
        val categoryId = field("category_id")
        val purposeStatus = field("purpose_status")
        val purposeId = field("purpose_id")
        if (categoryStatus !in setOf("ASSIGNED", "ABSTAINED") || purposeStatus !in setOf("ASSIGNED", "UNASSIGNED")) {
            ClassificationResult.Unusable("invalid_status")
        } else if (categoryStatus == "ABSTAINED" && categoryId == null && purposeStatus == "UNASSIGNED" && purposeId == null) {
            ClassificationResult.Abstained
        } else if (categoryStatus == "ASSIGNED" && categoryId in candidates.categoryIds &&
            ((purposeStatus == "UNASSIGNED" && purposeId == null) || (purposeStatus == "ASSIGNED" && purposeId in candidates.purposeIds))) {
            ClassificationResult.Assigned(categoryId!!, purposeId)
        } else ClassificationResult.Unusable("invalid_candidate_id_or_status")
    } catch (_: Exception) { ClassificationResult.Unusable("invalid_schema") }

    val outputSchema = Json.parseToJsonElement("""{
      "type":"object","additionalProperties":false,
      "required":["category_status","category_id","purpose_status","purpose_id"],
      "properties":{
        "category_status":{"type":"string","enum":["ASSIGNED","ABSTAINED"]},
        "category_id":{"type":["string","null"]},
        "purpose_status":{"type":"string","enum":["ASSIGNED","UNASSIGNED"]},
        "purpose_id":{"type":["string","null"]}
      }
    }""")
}
