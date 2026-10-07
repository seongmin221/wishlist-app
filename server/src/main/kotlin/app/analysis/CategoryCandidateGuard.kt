package app.analysis

import app.ai.TaxonomyCatalog
import kotlinx.serialization.json.*
import java.sql.Connection
import java.util.UUID

internal object CategoryCandidateGuard {
    private val publicIds=TaxonomyCatalog.loadV1().categories.map { it.id }.toSet()
    /** Owner lock already excludes category edits. Read category rows without acquiring locks after item/job. */
    fun valid(c:Connection,claim:AnalysisClaim):Boolean {
        val saved=c.prepareStatement("select candidate_snapshot_json,pending_category_id from analysis_jobs where id=?").use { s ->
            s.setObject(1,claim.jobId);s.executeQuery().use { r -> check(r.next());r.getString(1) to r.getString(2) }
        }
        if(saved.first==null) return saved.second==null || saved.second in publicIds
        return try {
            val snapshot=Json.parseToJsonElement(saved.first!!).jsonObject
            val ids=snapshot.getValue("categories").jsonArray.map { it.jsonPrimitive.takeIf { value -> value.isString }?.content ?: return false }.toSet()
            if (ids.isEmpty()) return false
            if (snapshot.getValue("purposes").jsonArray.any { it !is JsonPrimitive || !it.isString }) return false
            if(saved.second!=null && saved.second !in ids) return false
            val version=snapshot["schema_version"]?.jsonPrimitive?.intOrNull ?: 1
            if(version==1) return ids.all { it in publicIds }
            if(version!=2 || snapshot["owner_id"]?.jsonPrimitive?.content!=claim.ownerId.toString()) return false
            val custom=snapshot["custom_categories"]?.jsonObject ?: return false
            if(custom.keys.any { it !in ids }) return false
            // Modern provider ID sets are sealed; UUID custom IDs additionally require owned version records.
            if(ids.any { runCatching { UUID.fromString(it) }.isSuccess && it !in custom }) return false
            custom.all { (id,expected) ->
                val uuid=runCatching { UUID.fromString(id) }.getOrNull() ?: return@all false
                val row=expected.jsonObject
                val expectedVersion=row.getValue("version").jsonPrimitive.int
                if(expectedVersion <= 0 || !row.getValue("name").jsonPrimitive.isString || !row.getValue("parent_id").jsonPrimitive.isString ||
                    row.getValue("examples").jsonArray.any { it !is JsonPrimitive || !it.isString } ||
                    (row["description"] != null && row["description"] != JsonNull && (row["description"] !is JsonPrimitive || !row.getValue("description").jsonPrimitive.isString))) return@all false
                c.prepareStatement("select version from custom_categories where owner_id=? and id=? and deleted_at is null and ai_eligible").use { s ->
                    s.setObject(1,claim.ownerId);s.setObject(2,uuid);s.executeQuery().use { r ->
                        r.next() && r.getInt(1)==expectedVersion
                    }
                }
            }
        } catch (_: IllegalArgumentException) { false }
        catch (_: NoSuchElementException) { false }
        catch (_: IllegalStateException) { false }
    }
}
