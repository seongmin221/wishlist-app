package app.wishlist

import app.persistence.bindParameters
import java.sql.Connection
import java.sql.ResultSet
import java.time.ZoneOffset
import java.util.UUID

/** Exact count and navigation share one materialized action classification in the read snapshot. */
internal class WishlistWindowReader(private val repository: WishlistReadRepository) {
    fun read(c: Connection, owner: UUID, scope: ReadScope, window: ReadWindow): ReadPage {
        val source=if(scope is ReadScope.Action) {
            val classified=WishlistReadPredicates.classified(owner)
            SqlPredicate("with ${classified.sql}, eligible as not materialized (select id,created_at from classified where grp=?)",
                classified.parameters+scope.group.name)
        } else {
            val predicate=WishlistReadPredicates.scope(owner,scope)
            SqlPredicate("with eligible as not materialized (select i.id,i.created_at from wishlist_items i where ${predicate.sql})",predicate.parameters)
        }
        val selection=when(window) {
            is ReadWindow.Page -> pageSelection(window)
            is ReadWindow.Anchor -> anchorSelection(window)
        }
        val sql=source.sql+selection.sql+"""
            , edges as (
                select (select count(*) from eligible) total_count,
                    (select id from selected order by created_at desc,id desc limit 1) first_id,
                    (select created_at from selected order by created_at desc,id desc limit 1) first_at,
                    (select id from selected order by created_at asc,id asc limit 1) last_id,
                    (select created_at from selected order by created_at asc,id asc limit 1) last_at
            ), navigation as (
                select edges.*,
                    case when ? then false else exists(select 1 from eligible e where (e.created_at,e.id)>(first_at,first_id)) end has_previous,
                    exists(select 1 from eligible e where (e.created_at,e.id)<(last_at,last_id)) has_next
                from edges
            )
            select n.*,s.id,s.created_at,r.id recovery_id,r.created_at recovery_at,
                a.id anchor_id
            from navigation n
            left join selected s on true
            left join recovery r on true
            left join resolved a on true
            order by s.created_at desc,s.id desc
        """.trimIndent()
        return c.prepareStatement(sql).use { statement ->
            statement.bindParameters(source.parameters+selection.parameters+
                (window is ReadWindow.Page && window.boundary==null && window.direction==ReadDirection.OLDER))
            statement.executeQuery().use { rows ->
                check(rows.next())
                val count=rows.getLong("total_count")
                val recovery=position(rows,"recovery_id","recovery_at")
                val previous=position(rows,"first_id","first_at").takeIf { rows.getBoolean("has_previous") }
                val next=position(rows,"last_id","last_at").takeIf { rows.getBoolean("has_next") }
                val anchorId=rows.getObject("anchor_id",UUID::class.java)
                val selected=buildList {
                    do { position(rows,"id","created_at")?.let { add(it) } } while(rows.next())
                }
                val page=window as? ReadWindow.Page
                val anchor=window as? ReadWindow.Anchor
                ReadPage(repository.load(c,owner,selected),count,
                    previous ?: recovery.takeIf { page?.direction==ReadDirection.OLDER },
                    next ?: recovery.takeIf { page?.direction==ReadDirection.NEWER },
                    anchor?.position?.id,anchorId,anchor?.let { anchorId==it.position.id },
                    previousInclusive=recovery!=null && page?.direction==ReadDirection.OLDER,
                    nextInclusive=recovery!=null && page?.direction==ReadDirection.NEWER)
            }
        }
    }

    private fun pageSelection(window: ReadWindow.Page): SqlPredicate {
        require(window.limit in 1..100)
        val older=window.direction==ReadDirection.OLDER
        val boundary=window.boundary
        val comparison=if(older) "<" else ">"
        val order=if(older) "desc" else "asc"
        val condition=if(boundary==null) "" else "where (created_at,id) $comparison${if(window.boundaryInclusive) "=" else ""} (?,?)"
        val bounds=boundary?.let { listOf(it.createdAt.atOffset(ZoneOffset.UTC),it.id) } ?: emptyList()
        val recovery=if(boundary==null) "select id,created_at from eligible where false" else """
            select id,created_at from eligible
            where not exists(select 1 from selected) and (created_at,id) ${if(older) ">=" else "<="} (?,?)
            order by created_at ${if(older) "asc" else "desc"},id ${if(older) "asc" else "desc"} limit 1
        """.trimIndent()
        return SqlPredicate("""
            , selected as materialized (select id,created_at from eligible $condition order by created_at $order,id $order limit ?),
            recovery as ($recovery),
            resolved as (select id,created_at from eligible where false)
        """.trimIndent(),bounds+window.limit+bounds)
    }

    private fun anchorSelection(window: ReadWindow.Anchor): SqlPredicate {
        require(window.before in 0..20 && window.after in 0..20)
        val position=window.position
        val bounds=listOf(position.createdAt.atOffset(ZoneOffset.UTC),position.id)
        return SqlPredicate("""
            , original as (select id,created_at from eligible where id=?),
            older as (select id,created_at from eligible where (created_at,id)<(?,?) order by created_at desc,id desc limit 1),
            newer as (select id,created_at from eligible where (created_at,id)>(?,?) order by created_at asc,id asc limit 1),
            resolved as materialized (
                select * from original
                union all select * from older where not exists(select 1 from original)
                union all select * from newer where not exists(select 1 from original) and not exists(select 1 from older)
            ), before_anchor as (
                select e.id,e.created_at from eligible e,resolved a where (e.created_at,e.id)>(a.created_at,a.id)
                order by e.created_at asc,e.id asc limit ?
            ), after_anchor as (
                select e.id,e.created_at from eligible e,resolved a where (e.created_at,e.id)<(a.created_at,a.id)
                order by e.created_at desc,e.id desc limit ?
            ), selected as materialized (
                select * from before_anchor union all select * from resolved union all select * from after_anchor
            ), recovery as (select id,created_at from eligible where false)
        """.trimIndent(),listOf(position.id)+bounds+bounds+listOf(window.before,window.after))
    }

    private fun position(rows: ResultSet, idColumn: String, timeColumn: String): ReadPosition? =
        rows.getObject(idColumn,UUID::class.java)?.let { ReadPosition(rows.getTimestamp(timeColumn).toInstant(),it) }
}
