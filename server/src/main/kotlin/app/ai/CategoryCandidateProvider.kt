package app.ai

import app.analysis.AnalysisClaim
import app.category.CategoryRepository
import app.category.PublicCategoryRegistry
import app.purpose.PurposeCandidateReader
import java.sql.Connection

fun interface CategoryCandidateSupply {
    fun snapshot(connection: Connection, claim: AnalysisClaim): CandidateSnapshot
}

class CategoryCandidateProvider : CategoryCandidateSupply {
    private val catalog = TaxonomyCatalog.loadV1()
    private val registry = PublicCategoryRegistry(catalog)
    override fun snapshot(connection: Connection, claim: AnalysisClaim): CandidateSnapshot {
        val base = catalog.snapshot(catalog.categories.map { it.id }.toSet())
        val custom = CategoryRepository().all(connection, claim.ownerId).filter { it.aiEligible }.associate { row ->
            row.id.toString() to CustomCategoryCandidate(row.version, row.input.name, row.parentId, row.input.description, row.input.examples)
        }
        val labels = custom.mapValues { (_,row) -> "${registry.parent(row.parentId)!!.name} > ${row.name}" }
        val purposes = PurposeCandidateReader.read(connection, claim.ownerId, claim.itemId)
        return base.copy(categoryIds = base.categoryIds + custom.keys, categoryLabels = base.categoryLabels + labels,
            ownerId = claim.ownerId.toString(), customCategories = custom,
            purposeIds = purposes.map { it.id }.toCollection(LinkedHashSet()), schemaVersion = 3, purposeCandidates = purposes)
    }
}
