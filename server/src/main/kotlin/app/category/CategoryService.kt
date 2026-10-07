package app.category

import app.common.FieldChange
import app.ai.TaxonomyCatalog
import app.persistence.MutationReceipts
import app.persistence.OwnerStructureLock
import app.persistence.ReceiptTarget
import app.persistence.inTransaction
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.json.*

class CategoryService(private val dataSource: DataSource) {
    private val categories = CategoryRepository()
    private val registry = PublicCategoryRegistry(TaxonomyCatalog.loadV1())

    fun list(owner: UUID, scope: CategoryScope, parent: String?): CategoryList = dataSource.inTransaction(readOnly = true) { connection ->
        validateParent(parent)
        val custom = categories.all(connection, owner)
        val counts = publicCounts(connection, owner)
        val groups = registry.groups.filter { parent == null || it.id == parent }.mapNotNull { group ->
            val public = group.categories.map {
                CategoryEntry(it.id, it.name, it.parentId, "PUBLIC", it.displayOrder, counts[it.id] ?: 0)
            }
            val personal = custom.filter { it.parentId == group.id }.map { row ->
                CategoryEntry(row.id.toString(), row.input.name, row.parentId, "CUSTOM", row.displayOrder, row.itemCount)
            }
            val entries = (public + personal).filter {
                scope == CategoryScope.SELECT || it.kind == "CUSTOM" || it.itemCount > 0
            }
            if (scope == CategoryScope.BROWSE && entries.isEmpty()) null
            else CategoryGroup(group.id, group.name, group.displayOrder, entries.sumOf { it.itemCount }, entries)
        }
        CategoryList(scope, registry.taxonomyVersion, groups, custom.size)
    }

    fun get(owner: UUID, id: UUID): CustomCategory? = dataSource.inTransaction(readOnly = true) {
        categories.find(it, owner, id)
    }

    fun create(owner: UUID, key: UUID, parent: String, input: CategoryInput): CategoryCreation {
        validateParent(parent)
        validateInput(input)
        val fingerprint = fingerprint(parent, input)
        return dataSource.inTransaction { connection ->
            OwnerStructureLock.lock(connection, owner)
            val receipt = MutationReceipts.find(connection, owner, CREATE_OPERATION, key, ReceiptTarget.CATEGORY)
            if (receipt != null) {
                if (receipt.fingerprint != fingerprint) throw CategoryException("IDEMPOTENCY_KEY_REUSED")
                val row = categories.find(connection, owner, receipt.targetId)
                    ?: throw CategoryException("CATEGORY_NOT_AVAILABLE")
                return@inTransaction CategoryCreation(row, categories.count(connection, owner), true)
            }
            val retryAfter = MutationReceipts.retryAfterSeconds(connection, owner, CREATE_OPERATION, 5, 60)
            if (retryAfter != null) throw CategoryException("CATEGORY_CREATE_RATE_LIMITED", retryAfterSeconds = retryAfter)
            val used = categories.count(connection, owner)
            if (used >= 20) throw CategoryException("CATEGORY_LIMIT_REACHED")
            checkDuplicate(connection, owner, parent, input.name)
            val exclusion = CategoryAiSafetyPolicy.exclusionReason(input)
            val id = categories.insert(connection, owner, parent, input, exclusion == null, exclusion)
            MutationReceipts.save(connection, owner, CREATE_OPERATION, key, fingerprint, ReceiptTarget.CATEGORY, id)
            CategoryCreation(checkNotNull(categories.find(connection, owner, id)), used + 1, false)
        }
    }

    fun patch(owner: UUID, id: UUID, expectedVersion: Int, changes: FieldChanges): CustomCategory = dataSource.inTransaction { connection ->
        OwnerStructureLock.lock(connection, owner)
        val current = categories.find(connection, owner, id, lock = true) ?: throw CategoryException("CATEGORY_NOT_FOUND")
        if (current.version != expectedVersion) {
            throw CategoryException("CATEGORY_VERSION_CONFLICT", currentVersion = current.version)
        }
        val hasChanges = changes.name != null || changes.description != FieldChange.Keep || changes.examples != FieldChange.Keep
        if (!hasChanges || expectedVersion <= 0) throw CategoryException("INVALID_CATEGORY_INPUT")
        val input = CategoryInput(
            changes.name ?: current.input.name,
            when (val value = changes.description) {
                FieldChange.Keep -> current.input.description
                is FieldChange.Set -> value.value
            },
            when (val value = changes.examples) {
                FieldChange.Keep -> current.input.examples
                is FieldChange.Set -> value.value
            },
        )
        validateInput(input)
        if (input == current.input) return@inTransaction current
        checkDuplicate(connection, owner, current.parentId, input.name, id)
        val exclusion = CategoryAiSafetyPolicy.exclusionReason(input)
        categories.update(connection, owner, id, input, exclusion == null, exclusion)
        checkNotNull(categories.find(connection, owner, id))
    }

    private fun publicCounts(connection: Connection, owner: UUID): Map<String, Long> = connection.prepareStatement("""
        select category_id,count(*) from wishlist_items
        where owner_id=? and lifecycle_status='ACTIVE' and category_id is not null group by category_id
    """).use { statement ->
        statement.setObject(1, owner)
        statement.executeQuery().use { rows -> buildMap { while (rows.next()) put(rows.getString(1), rows.getLong(2)) } }
    }

    private fun checkDuplicate(connection: Connection, owner: UUID, parent: String, name: String, exceptId: UUID? = null) {
        if (categories.duplicate(connection, owner, parent, CategoryInputPolicy.normalizedName(name), exceptId)) {
            throw CategoryException("CATEGORY_NAME_DUPLICATE", setOf("name"))
        }
    }

    private fun validateParent(parent: String?) {
        if (parent != null && registry.parent(parent) == null) throw CategoryException("INVALID_CATEGORY_PARENT", setOf("parentId"))
    }

    private fun validateInput(input: CategoryInput) {
        val fields = CategoryInputPolicy.validate(input)
        if (fields.isNotEmpty()) throw CategoryException("INVALID_CATEGORY_INPUT", fields)
    }

    private fun fingerprint(parent: String, input: CategoryInput): String = MutationReceipts.fingerprint(JsonObject(linkedMapOf(
        "parentId" to JsonPrimitive(parent), "name" to JsonPrimitive(input.name),
        "description" to (input.description?.let(::JsonPrimitive) ?: JsonNull),
        "examples" to JsonArray(input.examples.map(::JsonPrimitive)),
    )))

    private companion object { const val CREATE_OPERATION = "CREATE_CUSTOM_CATEGORY" }
}
