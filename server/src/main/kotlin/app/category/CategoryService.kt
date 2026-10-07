package app.category

import app.ai.TaxonomyCatalog
import app.persistence.OwnerStructureLock
import java.security.MessageDigest
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.json.*

class CategoryService(private val dataSource: DataSource) {
    private val categories = CategoryRepository()
    private val registry = PublicCategoryRegistry(TaxonomyCatalog.loadV1())

    fun list(owner: UUID, scope: CategoryScope, parent: String?): CategoryList = transaction(readOnly = true) { connection ->
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

    fun get(owner: UUID, id: UUID): CustomCategory? = transaction(readOnly = true) {
        categories.find(it, owner, id)
    }

    fun create(owner: UUID, key: UUID, parent: String, input: CategoryInput): CategoryCreation {
        validateParent(parent)
        validateInput(input)
        val fingerprint = fingerprint(parent, input)
        return transaction { connection ->
            OwnerStructureLock.lock(connection, owner)
            val receipt = findReceipt(connection, owner, key)
            if (receipt != null) {
                if (receipt.first != fingerprint) throw CategoryException("IDEMPOTENCY_KEY_REUSED")
                val row = categories.find(connection, owner, receipt.second)
                    ?: throw CategoryException("CATEGORY_NOT_AVAILABLE")
                return@transaction CategoryCreation(row, categories.count(connection, owner), true)
            }
            val retryAfter = retryAfterSeconds(connection, owner)
            if (retryAfter != null) throw CategoryException("CATEGORY_CREATE_RATE_LIMITED", retryAfterSeconds = retryAfter)
            val used = categories.count(connection, owner)
            if (used >= 20) throw CategoryException("CATEGORY_LIMIT_REACHED")
            checkDuplicate(connection, owner, parent, input.name)
            val exclusion = CategoryAiSafetyPolicy.exclusionReason(input)
            val id = categories.insert(connection, owner, parent, input, exclusion == null, exclusion)
            saveReceipt(connection, owner, key, fingerprint, id)
            CategoryCreation(checkNotNull(categories.find(connection, owner, id)), used + 1, false)
        }
    }

    fun patch(owner: UUID, id: UUID, expectedVersion: Int, changes: CategoryChanges): CustomCategory = transaction { connection ->
        OwnerStructureLock.lock(connection, owner)
        val current = categories.find(connection, owner, id, lock = true) ?: throw CategoryException("CATEGORY_NOT_FOUND")
        if (current.version != expectedVersion) {
            throw CategoryException("CATEGORY_VERSION_CONFLICT", currentVersion = current.version)
        }
        val hasChanges = changes.name != null || changes.description != CategoryChange.Keep || changes.examples != CategoryChange.Keep
        if (!hasChanges || expectedVersion <= 0) throw CategoryException("INVALID_CATEGORY_INPUT")
        val input = CategoryInput(
            changes.name ?: current.input.name,
            when (val value = changes.description) {
                CategoryChange.Keep -> current.input.description
                is CategoryChange.Set -> value.value
            },
            when (val value = changes.examples) {
                CategoryChange.Keep -> current.input.examples
                is CategoryChange.Set -> value.value
            },
        )
        validateInput(input)
        if (input == current.input) return@transaction current
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

    private fun findReceipt(connection: Connection, owner: UUID, key: UUID): Pair<String, UUID>? = connection.prepareStatement("""
        select request_fingerprint,category_id from mutation_receipts
        where owner_id=? and operation='CREATE_CUSTOM_CATEGORY' and idempotency_key=?
    """).use { statement ->
        statement.setObject(1, owner)
        statement.setObject(2, key)
        statement.executeQuery().use { rows ->
            if (rows.next()) rows.getString(1) to rows.getObject(2, UUID::class.java) else null
        }
    }

    private fun retryAfterSeconds(connection: Connection, owner: UUID): Int? = connection.prepareStatement("""
        with instant as materialized (select clock_timestamp() as as_of)
        select case when count(*)>=5 then greatest(1,ceil(extract(epoch from
            min(created_at)+interval '60 seconds'-(select as_of from instant))))::integer end
        from mutation_receipts where owner_id=? and operation='CREATE_CUSTOM_CATEGORY'
            and created_at>(select as_of from instant)-interval '60 seconds'
    """).use { statement ->
        statement.setObject(1, owner)
        statement.executeQuery().use { rows -> check(rows.next()); rows.getObject(1) as Int? }
    }

    private fun saveReceipt(connection: Connection, owner: UUID, key: UUID, fingerprint: String, id: UUID) {
        connection.prepareStatement("""
            insert into mutation_receipts(owner_id,operation,idempotency_key,request_fingerprint,category_id)
            values (?,'CREATE_CUSTOM_CATEGORY',?,?,?)
        """).use { statement ->
            statement.setObject(1, owner)
            statement.setObject(2, key)
            statement.setString(3, fingerprint)
            statement.setObject(4, id)
            check(statement.executeUpdate() == 1)
        }
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

    private fun fingerprint(parent: String, input: CategoryInput): String {
        val canonical = JsonObject(linkedMapOf(
            "parentId" to JsonPrimitive(parent), "name" to JsonPrimitive(input.name),
            "description" to (input.description?.let(::JsonPrimitive) ?: JsonNull),
            "examples" to JsonArray(input.examples.map(::JsonPrimitive)),
        )).toString()
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun <T> transaction(readOnly: Boolean = false, block: (Connection) -> T): T = dataSource.connection.use { connection ->
        if (readOnly) {
            connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            connection.isReadOnly = true
        }
        connection.autoCommit = false
        try {
            val result = block(connection)
            connection.commit()
            result
        } catch (cause: Throwable) {
            connection.rollback()
            throw cause
        }
    }
}
