package app.http

import app.budget.BudgetMaintenanceReport
import app.maintenance.MaintenanceReport
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class MaintenanceRoutesTest {
    private fun report(failed: List<String>) = MaintenanceReport(3, 0, 1, 0, 0, 0, 0, 0, 0, BudgetMaintenanceReport(0, 0), failed)

    @Test fun `a clean run is 200 and a run with a failed step is 500 both with the report`() {
        for ((failed, status) in listOf(emptyList<String>() to HttpStatusCode.OK, listOf("reconcile") to HttpStatusCode.InternalServerError)) {
            testApplication {
                application { routing { maintenanceRoutes { report(failed) } } }
                val response = client.post("/internal/maintenance/run")
                assertEquals(status, response.status)
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(3, body.getValue("publishedEvents").jsonPrimitive.int)
            }
        }
    }

    @Test fun `a stopping runtime refuses new runs`() = testApplication {
        application { routing { maintenanceRoutes { null } } }
        assertEquals(HttpStatusCode.ServiceUnavailable, client.post("/internal/maintenance/run").status)
    }
}
