package app.budget

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** The notifier must deduplicate on [BudgetAlert.id] because delivery can be retried after a crash. */
class BudgetAlertDispatcher(private val dataSource: DataSource) {
    fun dispatch(limit: Int, notify: (BudgetAlert) -> Unit): Int {
        var delivered = 0
        repeat(limit.coerceAtLeast(0)) {
            val claimed = claim() ?: return delivered
            try {
                notify(claimed.alert)
                dataSource.connection.use { c -> c.prepareStatement("update llm_budget_alerts set delivered_at=now(),lease_until=null,claim_token=null where id=? and claim_token=? and delivered_at is null").use { s ->
                    s.setObject(1,claimed.alert.id); s.setObject(2,claimed.token); delivered += s.executeUpdate()
                } }
            } catch (_: Exception) {
                dataSource.connection.use { c -> c.prepareStatement("update llm_budget_alerts set lease_until=null,claim_token=null where id=? and claim_token=? and delivered_at is null").use { s ->
                    s.setObject(1,claimed.alert.id); s.setObject(2,claimed.token); s.executeUpdate()
                } }
                return delivered
            }
        }
        return delivered
    }

    private fun claim(): Claim? = dataSource.connection.use { c ->
        c.autoCommit = false
        try {
            val alert = c.prepareStatement(
                """select id,window_type,window_start,threshold_percent from llm_budget_alerts
                   where delivered_at is null and (lease_until is null or lease_until<now())
                   order by created_at for update skip locked limit 1""",
            ).use { s -> s.executeQuery().use { r -> if (r.next()) BudgetAlert(r.getObject(1,UUID::class.java),r.getString(2),r.getTimestamp(3).toInstant(),r.getInt(4)) else null } }
            val claim = alert?.let { Claim(it,UUID.randomUUID()) }
            if (claim != null) c.prepareStatement("update llm_budget_alerts set lease_until=?,claim_token=? where id=?").use { s ->
                s.setTimestamp(1,Timestamp.from(Instant.now().plusSeconds(120))); s.setObject(2,claim.token); s.setObject(3,claim.alert.id); s.executeUpdate()
            }
            c.commit(); claim
        } catch (error: Exception) { c.rollback(); throw error }
    }

    private data class Claim(val alert: BudgetAlert, val token: UUID)
}
