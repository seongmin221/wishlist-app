package app

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthRouteTest {
    @Test fun `worker runtime uses a pooled connection and releases it at application stop`() {
        app.testutil.PostgresTestContainer().use { database ->
            database.start()
            DatabaseFactory.migrate(database.jdbcUrl, database.username, database.password)
            val source = DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password)
            val resources = RuntimeResources()
            fun otherConnections(): String? = app.testutil.analysisScalar(source,
                "select count(*) from pg_stat_activity where datname=current_database() and usename=current_user and pid<>pg_backend_pid()")
            testApplication {
                application { module(mapOf("APP_ENV" to "local", "APP_ROLE" to "general-worker", "DATABASE_URL" to database.jdbcUrl,
                    "DATABASE_USER" to database.username, "DATABASE_PASSWORD" to database.password,
                    "OPENAI_API_KEY" to "fixture-only", "OPENAI_MODEL_SNAPSHOT" to "gpt-5.6-luna"), resources) }
                assertEquals(HttpStatusCode.OK, client.get("/health").status)
                val response = client.post("/internal/worker/general") {
                    setBody("""{"jobId":"00000000-0000-0000-0000-000000000001","generation":1}""")
                }
                assertEquals(HttpStatusCode.NoContent, response.status)
                assertEquals("1", otherConnections(), "returned connection should stay reusable in the role pool")
            }
            kotlin.test.assertTrue(resources.isClosed)
            assertEquals("0", otherConnections(), "application stop must close the actual runtime pool")
        }
    }

    @Test fun `each worker role exposes only its own internal route`() {
        app.testutil.PostgresTestContainer().use { database ->
            database.start()
            DatabaseFactory.migrate(database.jdbcUrl, database.username, database.password)
            val body = """{"jobId":"00000000-0000-0000-0000-000000000001","generation":1}"""
            for ((role, own, other) in listOf(Triple("general-worker", "general", "browser"), Triple("browser-worker", "browser", "general"))) {
                testApplication {
                    application { module(mapOf("APP_ENV" to "local", "APP_ROLE" to role, "DATABASE_URL" to database.jdbcUrl,
                        "DATABASE_USER" to database.username, "DATABASE_PASSWORD" to database.password,
                        "OPENAI_API_KEY" to "fixture-only", "OPENAI_MODEL_SNAPSHOT" to "gpt-5.6-luna"), RuntimeResources()) }
                    assertEquals(HttpStatusCode.NoContent, client.post("/internal/worker/$own") { setBody(body) }.status, role)
                    assertEquals(HttpStatusCode.NotFound, client.post("/internal/worker/$other") { setBody(body) }.status, role)
                }
            }
        }
    }

    @Test fun `maintenance role exposes only its run route`() {
        app.testutil.PostgresTestContainer().use { database ->
            database.start()
            DatabaseFactory.migrate(database.jdbcUrl, database.username, database.password)
            val queue = app.testutil.InMemoryTaskQueue()
            testApplication {
                application { module(mapOf("APP_ENV" to "local", "APP_ROLE" to "maintenance", "DATABASE_URL" to database.jdbcUrl,
                    "DATABASE_USER" to database.username, "DATABASE_PASSWORD" to database.password), RuntimeResources(), queue) }
                assertEquals(HttpStatusCode.OK, client.post("/internal/maintenance/run").status)
                assertEquals(HttpStatusCode.NotFound, client.post("/internal/worker/general") {
                    setBody("""{"jobId":"00000000-0000-0000-0000-000000000001","generation":1}""")
                }.status)
            }
        }
    }

    @Test fun `failed startup closes resources without waiting for normal shutdown`() {
        val resources = RuntimeResources()
        var closes = 0
        resources.own(AutoCloseable { closes++ })
        testApplication {
            application { module(mapOf("APP_ENV" to "production"), resources) }
            kotlin.test.assertFails { startApplication() }
            kotlin.test.assertTrue(resources.isClosed)
            assertEquals(1, closes)
        }
    }

    @Test fun `health only runtime closes owned resources when application stops`() {
        var closes = 0
        val resources = RuntimeResources()
        resources.own(AutoCloseable { closes++ })
        testApplication {
            application { module(emptyMap(), resources) }
            assertEquals(HttpStatusCode.OK, client.get("/health").status)
            assertEquals(0, closes)
        }
        assertEquals(1, closes)
        resources.close()
        assertEquals(1, closes)
    }

    @Test
    fun `health endpoint returns ok`() = testApplication {
        application { module() }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.bodyAsText())
    }
}
