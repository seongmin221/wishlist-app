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

    @Test fun `unwired worker role fails instead of serving health only`() {
        assertFailsWith<IllegalArgumentException> {
            RuntimeConfig.fromEnvironment(mapOf(
                "APP_ENV" to "production", "APP_ROLE" to "general-worker",
                "DATABASE_URL" to "jdbc:postgresql://example/db", "DATABASE_USER" to "user", "DATABASE_PASSWORD" to "password",
            ))
        }
    }
}
