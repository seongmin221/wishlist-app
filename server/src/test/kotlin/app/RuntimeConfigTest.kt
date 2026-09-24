package app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RuntimeConfigTest {
    @Test fun `local without credentials remains health only`() {
        assertEquals(RuntimeRole.LOCAL_HEALTH, RuntimeConfig.fromEnvironment(emptyMap()).role)
    }

    @Test fun `production api cannot start without credentials`() {
        assertFailsWith<IllegalArgumentException> {
            RuntimeConfig.fromEnvironment(mapOf("APP_ENV" to "production", "APP_ROLE" to "api"))
        }
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
