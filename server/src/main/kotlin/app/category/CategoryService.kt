package app.category

import app.ai.TaxonomyCatalog
import app.persistence.OwnerStructureLock
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource
import java.security.MessageDigest
import kotlinx.serialization.json.*

class CategoryService(private val dataSource: DataSource) {
    private val categories = CategoryRepository()
    private val registry = PublicCategoryRegistry(TaxonomyCatalog.loadV1())

    fun list(owner: UUID, scope: CategoryScope, parent: String?): CategoryList = transaction(readOnly = true) { c ->
        validateParent(parent)
        val custom = categories.all(c,owner)
        val counts = c.prepareStatement("select category_id,count(*) from wishlist_items where owner_id=? and lifecycle_status='ACTIVE' and category_id is not null group by category_id").use { s ->
            s.setObject(1,owner); s.executeQuery().use { r -> buildMap { while(r.next()) put(r.getString(1),r.getLong(2)) } }
        }
        val groups = registry.groups.filter { parent == null || it.id == parent }.mapNotNull { group ->
            val public = group.categories.map { CategoryEntry(it.id,it.name,it.parentId,"PUBLIC",it.displayOrder,counts[it.id] ?: 0) }
            val personal = custom.filter { it.parentId==group.id }.map { row ->
                CategoryEntry(row.id.toString(),row.input.name,row.parentId,"CUSTOM",row.displayOrder,row.itemCount)
            }
            val entries = (public+personal).filter { scope==CategoryScope.SELECT || it.kind=="CUSTOM" || it.itemCount>0 }
            if (scope==CategoryScope.BROWSE && entries.isEmpty()) null else CategoryGroup(group.id,group.name,group.displayOrder,entries.sumOf { it.itemCount },entries)
        }
        CategoryList(scope,registry.taxonomyVersion,groups,custom.size)
    }

    fun get(owner: UUID, id: UUID): CustomCategory? = transaction(readOnly = true) { categories.find(it,owner,id) }

    fun create(owner: UUID, key: UUID, parent: String, input: CategoryInput): CategoryCreation {
        validateParent(parent); validateInput(input)
        val fingerprint = fingerprint(parent,input)
        return transaction { c ->
            OwnerStructureLock.lock(c,owner)
            val receipt = c.prepareStatement("select request_fingerprint,category_id from mutation_receipts where owner_id=? and operation='CREATE_CUSTOM_CATEGORY' and idempotency_key=?").use { s ->
                s.setObject(1,owner); s.setObject(2,key); s.executeQuery().use { r -> if(r.next()) r.getString(1) to r.getObject(2,UUID::class.java) else null }
            }
            if(receipt!=null) {
                if(receipt.first!=fingerprint) throw CategoryException("IDEMPOTENCY_KEY_REUSED")
                val row = categories.find(c,owner,receipt.second) ?: throw CategoryException("CATEGORY_NOT_AVAILABLE")
                return@transaction CategoryCreation(row,categories.all(c,owner).size,true)
            }
            val retryAfter = c.prepareStatement("""with instant as materialized (select clock_timestamp() as as_of)
                select case when count(*)>=5 then greatest(1,ceil(extract(epoch from min(created_at)+interval '60 seconds'-(select as_of from instant))))::integer end
                from mutation_receipts where owner_id=? and operation='CREATE_CUSTOM_CATEGORY' and created_at>(select as_of from instant)-interval '60 seconds'""").use { s ->
                s.setObject(1,owner); s.executeQuery().use { r -> check(r.next()); r.getObject(1) as Int? }
            }
            if(retryAfter!=null) throw CategoryException("CATEGORY_CREATE_RATE_LIMITED",retryAfterSeconds=retryAfter)
            val used = categories.all(c,owner).size
            if(used>=20) throw CategoryException("CATEGORY_LIMIT_REACHED")
            if(categories.duplicate(c,owner,parent,CategoryInputPolicy.normalizedName(input.name))) throw CategoryException("CATEGORY_NAME_DUPLICATE",setOf("name"))
            val exclusion=CategoryAiSafetyPolicy.exclusionReason(input)
            val id = categories.insert(c,owner,parent,input,exclusion==null,exclusion)
            c.prepareStatement("insert into mutation_receipts(owner_id,operation,idempotency_key,request_fingerprint,category_id) values (?,'CREATE_CUSTOM_CATEGORY',?,?,?)").use { s ->
                s.setObject(1,owner); s.setObject(2,key); s.setString(3,fingerprint); s.setObject(4,id); check(s.executeUpdate()==1)
            }
            CategoryCreation(checkNotNull(categories.find(c,owner,id)),used+1,false)
        }
    }

    fun patch(owner: UUID, id: UUID, expectedVersion: Int, changes: CategoryChanges): CustomCategory = transaction { c ->
        OwnerStructureLock.lock(c,owner)
        val current = categories.find(c,owner,id,lock=true) ?: throw CategoryException("CATEGORY_NOT_FOUND")
        if(current.version!=expectedVersion) throw CategoryException("CATEGORY_VERSION_CONFLICT",currentVersion=current.version)
        if(changes.name==null && changes.description==CategoryChange.Keep && changes.examples==CategoryChange.Keep || expectedVersion<=0) throw CategoryException("INVALID_CATEGORY_INPUT")
        val input = CategoryInput(changes.name ?: current.input.name,
            when(val value=changes.description) { CategoryChange.Keep -> current.input.description; is CategoryChange.Set -> value.value },
            when(val value=changes.examples) { CategoryChange.Keep -> current.input.examples; is CategoryChange.Set -> value.value })
        validateInput(input)
        if(input==current.input) return@transaction current
        if(categories.duplicate(c,owner,current.parentId,CategoryInputPolicy.normalizedName(input.name),id)) throw CategoryException("CATEGORY_NAME_DUPLICATE",setOf("name"))
        val exclusion=CategoryAiSafetyPolicy.exclusionReason(input)
        categories.update(c,owner,id,input,exclusion==null,exclusion)
        checkNotNull(categories.find(c,owner,id))
    }

    private fun validateParent(parent: String?) {
        if(parent!=null && registry.parent(parent)==null) throw CategoryException("INVALID_CATEGORY_PARENT",setOf("parentId"))
    }
    private fun validateInput(input: CategoryInput) {
        val fields = CategoryInputPolicy.validate(input)
        if(fields.isNotEmpty()) throw CategoryException("INVALID_CATEGORY_INPUT",fields)
    }
    private fun fingerprint(parent: String,input: CategoryInput): String {
        val canonical = JsonObject(linkedMapOf("parentId" to JsonPrimitive(parent),"name" to JsonPrimitive(input.name),
            "description" to (input.description?.let(::JsonPrimitive) ?: JsonNull),"examples" to JsonArray(input.examples.map(::JsonPrimitive)))).toString()
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    private fun <T> transaction(readOnly: Boolean=false, block: (Connection)->T): T = dataSource.connection.use { c ->
        if(readOnly) { c.transactionIsolation=Connection.TRANSACTION_REPEATABLE_READ; c.isReadOnly=true }
        c.autoCommit=false
        try { val result=block(c); c.commit(); result } catch(cause: Throwable) { c.rollback(); throw cause }
    }
}
