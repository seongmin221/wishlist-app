package app.purpose

import app.ai.PurposeCandidate
import java.sql.Connection
import java.util.UUID

/** AI purpose evidence. Same connection as the candidate snapshot transaction; never called during remote I/O. */
object PurposeCandidateReader {
    const val MAX_CANDIDATES = 10
    const val ITEM_NAMES = 2
    const val ITEM_NAME_CODE_POINTS = 20

    fun read(connection: Connection, owner: UUID, excludeItem: UUID): List<PurposeCandidate> {
        val rows = connection.prepareStatement("""select id,name,description from purposes where owner_id=? and lifecycle_status='ACTIVE'
            order by activity_at desc,id desc limit ?""").use { s ->
            s.setObject(1, owner); s.setInt(2, MAX_CANDIDATES)
            s.executeQuery().use { r -> buildList { while (r.next()) add(Triple(r.getObject(1, UUID::class.java), r.getString(2), r.getString(3))) } }
        }
        if (rows.isEmpty()) return emptyList()
        val array = connection.createArrayOf("uuid", rows.map { it.first }.toTypedArray())
        val names = try {
            connection.prepareStatement("""select purpose_id,product_name from (
                select purpose_id,product_name,row_number() over (partition by purpose_id order by created_at desc,id desc) rank
                from wishlist_items where owner_id=? and lifecycle_status='ACTIVE' and purpose_id=any(?) and id<>?
                    and nullif(btrim(product_name),'') is not null) ranked where rank<=? order by purpose_id,rank""").use { s ->
                s.setObject(1, owner); s.setArray(2, array); s.setObject(3, excludeItem); s.setInt(4, ITEM_NAMES)
                s.executeQuery().use { r -> buildList { while (r.next()) add(r.getObject(1, UUID::class.java) to truncate(r.getString(2))) } }
            }.groupBy({ it.first }, { it.second })
        } finally { array.free() }
        return rows.map { (id, name, description) -> PurposeCandidate(id.toString(), name, description, names[id].orEmpty()) }
    }

    private fun truncate(text: String): String =
        text.codePoints().limit(ITEM_NAME_CODE_POINTS.toLong()).toArray().let { String(it, 0, it.size) }
}
