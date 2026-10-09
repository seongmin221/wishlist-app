package app.purpose

import java.sql.Connection
import java.util.UUID

object PurposeMembership {
    /** Caller holds the owner structure lock. One item leaves [from] and joins [to]. */
    fun recordTransition(connection: Connection, owner: UUID, from: UUID?, to: UUID?) {
        if (from == to) return
        if (from != null) connection.prepareStatement(
            "update purposes set membership_version=membership_version+1 where owner_id=? and id=?",
        ).use { s -> s.setObject(1, owner); s.setObject(2, from); check(s.executeUpdate() == 1) }
        if (to != null) connection.prepareStatement("""update purposes set membership_version=membership_version+1,
            activity_at=clock_timestamp(),activity_kind='CANDIDATE_ADDED' where owner_id=? and id=?""").use { s ->
            s.setObject(1, owner); s.setObject(2, to); check(s.executeUpdate() == 1)
        }
    }
}
