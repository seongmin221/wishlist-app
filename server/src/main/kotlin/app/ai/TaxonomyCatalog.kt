package app.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class TaxonomyCategory(val id: String, val group: String, val name: String)
data class TaxonomyGroup(val name: String, val categories: List<TaxonomyCategory>)

class TaxonomyCatalog private constructor(val version: String, val groups: List<TaxonomyGroup>) {
    val categories: List<TaxonomyCategory> = groups.flatMap { it.categories }

    init {
        require(version.isNotBlank() && groups.isNotEmpty())
        require(categories.map { it.id }.distinct().size == categories.size) { "Duplicate taxonomy ID" }
        require(categories.all { it.id.isNotBlank() && it.name.isNotBlank() })
    }

    fun snapshot(ids: Set<String>): CandidateSnapshot {
        require(ids.isNotEmpty())
        val selected = categories.filter { it.id in ids }
        require(selected.size == ids.size) { "Unknown taxonomy ID" }
        return CandidateSnapshot(selected.map { it.id }.toSet(), emptySet(), selected.associate { it.id to "${it.group} > ${it.name}" })
    }

    companion object {
        fun loadV1(): TaxonomyCatalog {
            val raw = requireNotNull(TaxonomyCatalog::class.java.classLoader.getResourceAsStream("taxonomy/v1.json")) { "Missing v1 taxonomy resource" }
                .bufferedReader().use { it.readText() }
            val root = Json.parseToJsonElement(raw).jsonObject
            val groups = root.getValue("groups").jsonArray.map { group ->
                val value = group.jsonObject
                val name = value.getValue("name").jsonPrimitive.content
                TaxonomyGroup(name,value.getValue("categories").jsonArray.map { row ->
                    val pair = row.jsonArray
                    require(pair.size == 2)
                    TaxonomyCategory(pair[0].jsonPrimitive.content,name,pair[1].jsonPrimitive.content)
                })
            }
            return TaxonomyCatalog(root.getValue("version").jsonPrimitive.content,groups)
        }
    }
}
