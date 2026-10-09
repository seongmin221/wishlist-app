package db.migration

import app.persistence.ReadIndexRollout
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension
import java.util.zip.CRC32

/** Published V15 stays immutable. Do not change this migration after sharing it. */
class V16__recoverable_read_indexes : BaseJavaMigration() {
    override fun canExecuteInTransaction() = false
    // processResources packages these exact execution sources, independent of compiler bytecode.
    override fun getChecksum(): Int = CRC32().apply {
        for(path in listOf("db/migration/V16__recoverable_read_indexes.kt","app/persistence/ReadIndexRollout.kt")) {
            V16__recoverable_read_indexes::class.java.getResourceAsStream("/migration-checksums/V16/$path").use { source ->
                update(checkNotNull(source) { "Missing V16 execution source: $path" }.readBytes())
            }
        }
    }.value.toInt()
    override fun migrate(context: Context) {
        check(!context.configuration.getConfigurationExtension(PostgreSQLConfigurationExtension::class.java).isTransactionalLock) {
            "Use the shared Flyway configuration with postgresql.transactional.lock=false for V16"
        }
        ReadIndexRollout.apply(context.connection)
    }
}
