package app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RuntimeConfigTest {
    @Test fun `runtime selects bounded pool defaults and validates explicit override`() {
        val api = mapOf("DATABASE_URL" to "jdbc:postgresql://example/db", "DATABASE_USER" to "user",
            "DATABASE_PASSWORD" to "password", "FIREBASE_PROJECT_ID" to "project")
        val worker = api + mapOf("APP_ROLE" to "general-worker", "OPENAI_API_KEY" to "secret", "OPENAI_MODEL_SNAPSHOT" to "local-model")
        assertEquals(5, RuntimeConfig.fromEnvironment(api).databasePool.maximumPoolSize)
        assertEquals(2, RuntimeConfig.fromEnvironment(worker).databasePool.maximumPoolSize)
        assertEquals(7, RuntimeConfig.fromEnvironment(api + ("DB_POOL_MAX_SIZE" to "7")).databasePool.maximumPoolSize)
        assertEquals(5000L, RuntimeConfig.fromEnvironment(api).databasePool.connectionTimeoutMs)
        for (value in listOf("0", "-1", "bad", "", "2147483648")) {
            assertFailsWith<IllegalArgumentException> { RuntimeConfig.fromEnvironment(api + ("DB_POOL_MAX_SIZE" to value)) }
        }
    }

    @Test fun `local without credentials remains health only`() {
        assertEquals(RuntimeRole.LOCAL_HEALTH, RuntimeConfig.fromEnvironment(emptyMap()).role)
    }

    @Test fun `production api cannot start without credentials`() {
        assertFailsWith<IllegalArgumentException> {
            RuntimeConfig.fromEnvironment(mapOf("APP_ENV" to "production", "APP_ROLE" to "api"))
        }
    }

    @Test fun `browser worker needs the same settings as the general worker and a small pool`() {
        val db = mapOf("DATABASE_URL" to "jdbc:postgresql://example/db", "DATABASE_USER" to "user", "DATABASE_PASSWORD" to "password")
        val browser = db + mapOf("APP_ENV" to "production", "APP_ROLE" to "browser-worker")
        assertFailsWith<IllegalArgumentException> { RuntimeConfig.fromEnvironment(browser) }
        val config = RuntimeConfig.fromEnvironment(browser + mapOf("OPENAI_API_KEY" to "secret", "OPENAI_MODEL_SNAPSHOT" to "gpt-5.6-luna-2026-09-01"))
        assertEquals(RuntimeRole.BROWSER_WORKER, config.role)
        assertEquals(2, config.databasePool.maximumPoolSize)
        assertFailsWith<IllegalArgumentException> { RuntimeConfig.fromEnvironment(browser + mapOf("OPENAI_API_KEY" to "secret", "OPENAI_MODEL_SNAPSHOT" to "gpt-5.6-luna")) }
        assertFailsWith<IllegalArgumentException> { RuntimeConfig.fromEnvironment(db + ("APP_ROLE" to "scheduler")) }
    }

    @Test fun `maintenance requires the database and production queue settings`() {
        val db = mapOf("DATABASE_URL" to "jdbc:postgresql://example/db", "DATABASE_USER" to "user", "DATABASE_PASSWORD" to "password")
        assertFailsWith<IllegalArgumentException> { RuntimeConfig.fromEnvironment(mapOf("APP_ROLE" to "maintenance")) }
        val local = RuntimeConfig.fromEnvironment(db + ("APP_ROLE" to "maintenance"))
        assertEquals(RuntimeRole.MAINTENANCE, local.role)
        assertEquals(2, local.databasePool.maximumPoolSize)
        val production = db + mapOf("APP_ROLE" to "maintenance", "APP_ENV" to "production")
        assertFailsWith<IllegalArgumentException> { RuntimeConfig.fromEnvironment(production) }
        assertEquals(RuntimeRole.MAINTENANCE, RuntimeConfig.fromEnvironment(production + mapOf("TASKS_PROJECT_ID" to "p",
            "GENERAL_WORKER_URL" to "https://g", "BROWSER_WORKER_URL" to "https://b", "TASKS_CALLER_SERVICE_ACCOUNT" to "sa",
            "MEDIA_BUCKET" to "media")).role)
    }

    @Test fun `api and maintenance choose media storage and production requires a bucket`() {
        val db = mapOf("DATABASE_URL" to "jdbc:postgresql://example/db", "DATABASE_USER" to "user", "DATABASE_PASSWORD" to "password")
        val tasks = mapOf("TASKS_PROJECT_ID" to "p", "GENERAL_WORKER_URL" to "https://g", "BROWSER_WORKER_URL" to "https://b",
            "TASKS_CALLER_SERVICE_ACCOUNT" to "sa")
        val productionApi = db + tasks + mapOf("APP_ENV" to "production", "FIREBASE_PROJECT_ID" to "project")
        val productionMaintenance = db + tasks + mapOf("APP_ENV" to "production", "APP_ROLE" to "maintenance")
        for (env in listOf(productionApi, productionMaintenance)) {
            assertFailsWith<IllegalArgumentException> { RuntimeConfig.fromEnvironment(env) }
            assertFailsWith<IllegalArgumentException> { RuntimeConfig.fromEnvironment(env + ("MEDIA_BUCKET" to " ")) }
            val config = RuntimeConfig.fromEnvironment(env + ("MEDIA_BUCKET" to "wishlist-media"))
            assertEquals(MediaStorageConfig.Gcs("wishlist-media"), config.media)
            assertEquals(false, config.media!!.exposesLocalRoutes)
        }
        val localApi = db + ("FIREBASE_PROJECT_ID" to "project")
        val local = RuntimeConfig.fromEnvironment(localApi).media
        assertEquals(MediaStorageConfig.Local("build/local-media", "http://localhost:8080"), local)
        assertEquals(true, local!!.exposesLocalRoutes)
        assertEquals(MediaStorageConfig.Local("/tmp/m", "http://10.0.2.2:9000"), RuntimeConfig.fromEnvironment(localApi + mapOf(
            "MEDIA_LOCAL_DIR" to "/tmp/m", "MEDIA_LOCAL_BASE_URL" to "http://10.0.2.2:9000")).media)
        assertEquals(MediaStorageConfig.Local("build/local-media", "http://localhost:9000"),
            RuntimeConfig.fromEnvironment(localApi + ("PORT" to "9000")).media)
        assertEquals(MediaStorageConfig.Gcs("dev-bucket"), RuntimeConfig.fromEnvironment(localApi + ("MEDIA_BUCKET" to "dev-bucket")).media)
        assertEquals(MediaStorageConfig.Local("build/local-media", "http://localhost:8080"),
            RuntimeConfig.fromEnvironment(db + ("APP_ROLE" to "maintenance")).media)
        val worker = db + mapOf("APP_ROLE" to "general-worker", "OPENAI_API_KEY" to "secret", "OPENAI_MODEL_SNAPSHOT" to "local-model")
        assertNull(RuntimeConfig.fromEnvironment(worker).media)
        assertNull(RuntimeConfig.fromEnvironment(emptyMap()).media)
    }

    @Test fun `worker requires database and OpenAI settings`() {
        val worker = mapOf(
                "APP_ENV" to "production", "APP_ROLE" to "general-worker",
                "DATABASE_URL" to "jdbc:postgresql://example/db", "DATABASE_USER" to "user", "DATABASE_PASSWORD" to "password",
        )
        assertFailsWith<IllegalArgumentException> { RuntimeConfig.fromEnvironment(worker) }
        assertEquals(RuntimeRole.GENERAL_WORKER, RuntimeConfig.fromEnvironment(worker + mapOf(
            "OPENAI_API_KEY" to "secret", "OPENAI_MODEL_SNAPSHOT" to "gpt-5.6-luna-2026-09-01",
        )).role)
        assertFailsWith<IllegalArgumentException> { RuntimeConfig.fromEnvironment(worker + mapOf(
            "OPENAI_API_KEY" to "secret", "OPENAI_MODEL_SNAPSHOT" to "gpt-5.6-luna",
        )) }
        assertEquals(RuntimeRole.GENERAL_WORKER, RuntimeConfig.fromEnvironment(worker + mapOf(
            "APP_ENV" to "local", "OPENAI_API_KEY" to "secret", "OPENAI_MODEL_SNAPSHOT" to "gpt-5.6-luna",
        )).role)
    }
}
