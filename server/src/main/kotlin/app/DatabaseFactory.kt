package app

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.configuration.FluentConfiguration
import java.util.Properties
import org.postgresql.ds.PGSimpleDataSource
import javax.sql.DataSource

data class DatabasePoolConfig(val maximumPoolSize: Int, val connectionTimeoutMs: Long = 5000) {
    init {
        require(maximumPoolSize > 0) { "DB pool size must be positive" }
        require(connectionTimeoutMs >= 250) { "DB connection timeout must be at least 250ms" }
    }
}

object DatabaseFactory {
    fun dataSource(url: String, username: String, password: String): DataSource = PGSimpleDataSource().apply {
        setURL(url)
        user = username
        this.password = password
    }

    fun pooledDataSource(url: String, username: String, password: String, config: DatabasePoolConfig): HikariDataSource =
        HikariDataSource(HikariConfig().apply {
            dataSource = DatabaseFactory.dataSource(url, username, password)
            maximumPoolSize = config.maximumPoolSize
            minimumIdle = 0
            connectionTimeout = config.connectionTimeoutMs
            poolName = "wishlist-db"
        })

    /** Application and tests use this factory; CLI reads the same resource via -configFiles. */
    fun migrationConfiguration(url: String, username: String, password: String): FluentConfiguration {
        val settings=Properties().apply {
            DatabaseFactory::class.java.getResourceAsStream("/flyway.conf").use { stream ->
                checkNotNull(stream) { "Missing shared Flyway settings" };load(stream)
            }
        }
        return Flyway.configure().configuration(settings).dataSource(url,username,password)
    }
    fun migrate(url: String, username: String, password: String) {
        migrationConfiguration(url,username,password).load().migrate()
    }
}
