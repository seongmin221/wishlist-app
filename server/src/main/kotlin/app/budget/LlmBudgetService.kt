package app.budget

import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

enum class ReservationState { RESERVED, IN_FLIGHT, SETTLED, RELEASED }
data class BudgetReservation(val id: UUID, val requestId: UUID, val maximumMicrousd: Long)
sealed interface ReserveResult {
    data class Reserved(val reservation: BudgetReservation) : ReserveResult
    data object Exceeded : ReserveResult
}
data class WindowTotals(val reserved: Long, val settled: Long)
data class BudgetAlert(val id: UUID, val windowType: String, val windowStart: Instant, val thresholdPercent: Int)

class LlmBudgetService(
    private val dataSource: DataSource,
    private val price: PriceTable = PriceTable(),
    private val modelSnapshot: String = "test-snapshot",
    private val dailyCeilingMicrousd: Long = 600_000,
    private val monthlyCeilingMicrousd: Long = 6_000_000,
    private val allowLocalAlias: Boolean = false,
) {
    init { require(modelSnapshot.isNotBlank() && (allowLocalAlias || modelSnapshot != "gpt-5.6-luna")) }

    fun reserveBeforeCall(jobId: UUID, generation: Int, requestId: UUID): ReserveResult = transaction { c ->
        c.prepareStatement("select id, maximum_microusd, state from llm_budget_reservations where request_id=?").use { s ->
            s.setObject(1, requestId)
            s.executeQuery().use { r -> if (r.next()) return@transaction ReserveResult.Reserved(BudgetReservation(r.getObject(1, UUID::class.java), requestId, r.getLong(2))) }
        }
        val valid = c.prepareStatement("select 1 from analysis_jobs j join wishlist_items i on i.id=j.wishlist_item_id where j.id=? and j.generation=? and i.lifecycle_status='ACTIVE' and j.stage in ('GENERAL_RUNNING','BROWSER_RUNNING','CLASSIFICATION_RUNNING')").use { s ->
            s.setObject(1, jobId); s.setInt(2, generation); s.executeQuery().use { it.next() }
        }
        require(valid) { "Inactive or unclaimed analysis job" }
        val now = Instant.now()
        val windows = windows(now)
        val maximum = price.maximumMicrousd()
        windows.forEach { (type, start, ceiling) ->
            c.prepareStatement("insert into llm_budget_windows(id,window_type,window_start,ceiling_microusd) values(?,?,?,?) on conflict(window_type,window_start) do nothing").use { s ->
                s.setObject(1, UUID.randomUUID()); s.setString(2, type); s.setTimestamp(3, Timestamp.from(start)); s.setLong(4, ceiling); s.executeUpdate()
            }
        }
        windows.forEach { (type, start, ceiling) ->
            val allowed = c.prepareStatement("select reserved_microusd,settled_microusd,ceiling_microusd from llm_budget_windows where window_type=? and window_start=? for update").use { s ->
                s.setString(1, type); s.setTimestamp(2, Timestamp.from(start)); s.executeQuery().use { r -> r.next(); r.getLong(3) == ceiling && r.getLong(1) + r.getLong(2) + maximum <= ceiling }
            }
            if (!allowed) return@transaction ReserveResult.Exceeded
        }
        windows.forEach { (type, start, ceiling) ->
            c.adjustWindow(type, start, maximum, 0)
            val total = c.prepareStatement("select reserved_microusd+settled_microusd from llm_budget_windows where window_type=? and window_start=?").use { s ->
                s.setString(1,type); s.setTimestamp(2,Timestamp.from(start)); s.executeQuery().use { r -> r.next(); r.getLong(1) }
            }
            if (total >= ceiling - ceiling / 5) c.prepareStatement("insert into llm_budget_alerts(id,window_type,window_start,threshold_percent) values(?,?,?,80) on conflict do nothing").use { s ->
                s.setObject(1,UUID.randomUUID()); s.setString(2,type); s.setTimestamp(3,Timestamp.from(start)); s.executeUpdate()
            }
        }
        val id = UUID.randomUUID()
        c.prepareStatement("insert into llm_budget_reservations(id,request_id,analysis_job_id,generation,price_table_version,model_snapshot,state,maximum_microusd,lease_until) values(?,?,?,?,?,?,'RESERVED',?,?)").use { s ->
            s.setObject(1, id); s.setObject(2, requestId); s.setObject(3, jobId); s.setInt(4, generation)
            s.setString(5, price.version); s.setString(6, modelSnapshot); s.setLong(7, maximum); s.setTimestamp(8, Timestamp.from(now.plusSeconds(120))); s.executeUpdate()
        }
        ReserveResult.Reserved(BudgetReservation(id, requestId, maximum))
    }

    fun markInFlight(id: UUID) = transaction { c ->
        c.prepareStatement("update llm_budget_reservations set state='IN_FLIGHT', lease_until=? where id=? and state='RESERVED'").use { s ->
            s.setTimestamp(1, Timestamp.from(Instant.now().plusSeconds(120))); s.setObject(2, id)
            check(s.executeUpdate() == 1) { "Reservation cannot enter flight" }
        }
    }

    fun settle(id: UUID, inputTokens: Int, outputTokens: Int) = finalize(id, price.costMicrousd(inputTokens, outputTokens), false)

    fun settleMaximum(id: UUID) = finalize(id, price.maximumMicrousd(), false)

    fun reconcileExpired(now: Instant = Instant.now()): Int = transaction { c ->
        val expired = c.prepareStatement("select id,state,maximum_microusd from llm_budget_reservations where state in ('RESERVED','IN_FLIGHT') and lease_until<? for update skip locked").use { s ->
            s.setTimestamp(1, Timestamp.from(now)); s.executeQuery().use { r -> buildList { while (r.next()) add(Triple(r.getObject(1, UUID::class.java), ReservationState.valueOf(r.getString(2)), r.getLong(3))) } }
        }
        expired.forEach { (id, state, maximum) -> c.finalizeLocked(id, if (state == ReservationState.IN_FLIGHT) maximum else 0, state == ReservationState.RESERVED) }
        expired.size
    }

    fun state(id: UUID): ReservationState = dataSource.connection.use { c -> c.prepareStatement("select state from llm_budget_reservations where id=?").use { s -> s.setObject(1,id); s.executeQuery().use { r -> check(r.next()); ReservationState.valueOf(r.getString(1)) } } }
    fun windowTotals(type: String): WindowTotals = dataSource.connection.use { c -> c.prepareStatement("select reserved_microusd,settled_microusd from llm_budget_windows where window_type=? order by window_start desc limit 1").use { s -> s.setString(1,type); s.executeQuery().use { r -> check(r.next()); WindowTotals(r.getLong(1), r.getLong(2)) } } }
    fun pendingAlerts(): List<BudgetAlert> = dataSource.connection.use { c -> c.prepareStatement("select id,window_type,window_start,threshold_percent from llm_budget_alerts where delivered_at is null order by created_at").use { s -> s.executeQuery().use { r -> buildList { while (r.next()) add(BudgetAlert(r.getObject(1,UUID::class.java),r.getString(2),r.getTimestamp(3).toInstant(),r.getInt(4))) } } } }

    private fun finalize(id: UUID, actual: Long, release: Boolean) = transaction { c ->
        val state = c.prepareStatement("select state from llm_budget_reservations where id=? for update").use { s -> s.setObject(1,id); s.executeQuery().use { r -> check(r.next()); ReservationState.valueOf(r.getString(1)) } }
        check(state == ReservationState.IN_FLIGHT) { "Only in-flight reservations can settle" }
        c.finalizeLocked(id, actual, release)
    }

    private fun Connection.finalizeLocked(id: UUID, actual: Long, release: Boolean) {
        val reservation = prepareStatement("select maximum_microusd,created_at from llm_budget_reservations where id=?").use { s -> s.setObject(1,id); s.executeQuery().use { r -> r.next(); r.getLong(1) to r.getTimestamp(2).toInstant() } }
        require(actual in 0..reservation.first)
        windows(reservation.second).forEach { (type, start, _) -> adjustWindow(type, start, -reservation.first, actual) }
        prepareStatement("update llm_budget_reservations set state=?,actual_microusd=?,settled_at=now() where id=?").use { s ->
            s.setString(1, if (release) "RELEASED" else "SETTLED"); s.setLong(2, actual); s.setObject(3,id); s.executeUpdate()
        }
    }

    private fun Connection.adjustWindow(type: String, start: Instant, reservedDelta: Long, settledDelta: Long) = prepareStatement("update llm_budget_windows set reserved_microusd=reserved_microusd+?,settled_microusd=settled_microusd+? where window_type=? and window_start=?").use { s ->
        s.setLong(1,reservedDelta); s.setLong(2,settledDelta); s.setString(3,type); s.setTimestamp(4,Timestamp.from(start)); check(s.executeUpdate()==1)
    }

    private fun windows(time: Instant): List<Triple<String,Instant,Long>> {
        val utc = time.atZone(ZoneOffset.UTC)
        return listOf(Triple("DAILY", utc.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant(), dailyCeilingMicrousd), Triple("MONTHLY", utc.withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant(), monthlyCeilingMicrousd))
    }

    private fun <T> transaction(block: (Connection) -> T): T = dataSource.connection.use { c ->
        c.autoCommit = false
        try { val result = block(c); c.commit(); result } catch (error: Exception) { c.rollback(); throw error }
    }
}
