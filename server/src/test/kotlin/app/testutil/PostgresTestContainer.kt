package app.testutil

import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.containers.wait.strategy.WaitAllStrategy

/** A VM's forwarded host port can become reachable after PostgreSQL emits its ready log. */
class PostgresTestContainer : PostgreSQLContainer<PostgresTestContainer>("postgres:16-alpine") {
    init {
        waitingFor(
            WaitAllStrategy()
                .withStrategy(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))
                .withStrategy(Wait.forListeningPort()),
        )
    }
}
