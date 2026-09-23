package app

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager

class DatabaseMigrationTest {
    @Test
    fun `all Flyway migrations apply to an empty postgres database`() {
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { database ->
            database.start()

            assertDoesNotThrow {
                DatabaseFactory.migrate(database.jdbcUrl, database.username, database.password)
            }

            DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("select count(*) from flyway_schema_history where success").use { rows ->
                        rows.next()
                        assertEquals(6, rows.getInt(1))
                    }
                }
            }
        }
    }
}
