package app.home

import app.wishlist.*
import app.persistence.bindParameters
import java.sql.Connection
import java.util.UUID

class HomeReadRepository {
    fun groupSummaries(c: Connection, owner: UUID): List<HomeGroupKeys> {
        val classified=WishlistReadPredicates.classified(owner)
        val sql = """
            with ${classified.sql}, counts as (
                select count(*) filter (where grp = 'ANALYSIS_IN_PROGRESS') as analysis_count,
                       count(*) filter (where grp = 'INFORMATION_COMPLETION') as information_count,
                       count(*) filter (where grp = 'CLASSIFICATION_REVIEW') as review_count
                from classified
            ), ranked as (
                select id, created_at, grp,
                       row_number() over (partition by grp order by created_at desc, id desc) as rn
                from classified where grp is not null
            )
            select groups.grp, groups.cnt, ranked.id, ranked.created_at
            from counts cross join lateral (values
                (1, 'ANALYSIS_IN_PROGRESS', analysis_count),
                (2, 'INFORMATION_COMPLETION', information_count),
                (3, 'CLASSIFICATION_REVIEW', review_count)
            ) groups(ord, grp, cnt)
            left join ranked on ranked.grp = groups.grp and ranked.rn <= 4
            order by groups.ord, ranked.created_at desc, ranked.id desc
        """.trimIndent()
        return c.prepareStatement(sql).use { statement ->
            statement.bindParameters(classified.parameters)
            statement.executeQuery().use { rs ->
                val counts = linkedMapOf<HomeActionGroup, Long>()
                val positions = mutableMapOf<HomeActionGroup, MutableList<ReadPosition>>()
                while (rs.next()) {
                    val g = HomeActionGroup.valueOf(rs.getString("grp"))
                    counts[g] = rs.getLong("cnt")
                    val id = rs.getObject("id", UUID::class.java)
                    if (id != null) positions.getOrPut(g) { mutableListOf() }
                        .add(ReadPosition(rs.getTimestamp("created_at").toInstant(), id))
                }
                counts.map { (g, count) -> HomeGroupKeys(g, count, positions[g].orEmpty()) }
            }
        }
    }
}
