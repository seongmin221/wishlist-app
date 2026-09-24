package app.budget

import app.DatabaseFactory
import javax.sql.DataSource

data class MaintenanceReport(val expiredReservations: Int, val deliveredAlerts: Int)

data class BudgetMaintenanceConfig(val databaseUrl: String, val databaseUser: String, val databasePassword: String) {
    companion object {
        fun fromEnvironment(env: Map<String,String>): BudgetMaintenanceConfig {
            fun required(name: String) = env[name]?.takeIf(String::isNotBlank) ?: throw IllegalArgumentException("Missing $name")
            return BudgetMaintenanceConfig(required("DATABASE_URL"),required("DATABASE_USER"),required("DATABASE_PASSWORD"))
        }
    }
}

class BudgetMaintenanceService(private val dataSource: DataSource, private val notify: (BudgetAlert) -> Unit) {
    fun runOnce(): MaintenanceReport = MaintenanceReport(
        expiredReservations = LlmBudgetService(dataSource).reconcileExpired(),
        deliveredAlerts = BudgetAlertDispatcher(dataSource).dispatch(100, notify),
    )
}

/** Invoke once per minute from a scheduler-managed private job; never migrate the schema at runtime. */
fun main() {
    val config = BudgetMaintenanceConfig.fromEnvironment(System.getenv())
    val source = DatabaseFactory.dataSource(config.databaseUrl,config.databaseUser,config.databasePassword)
    val report = BudgetMaintenanceService(source) { alert ->
        // Cloud Run captures stderr; infrastructure must route these structured records to an alert channel.
        System.err.println("LLM_BUDGET_ALERT id=${alert.id} window=${alert.windowType} start=${alert.windowStart} threshold=${alert.thresholdPercent}")
    }.runOnce()
    println("budget_maintenance expired_reservations=${report.expiredReservations} delivered_alerts=${report.deliveredAlerts}")
}
