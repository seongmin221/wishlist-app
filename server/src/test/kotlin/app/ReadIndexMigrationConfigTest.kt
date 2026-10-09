package app

import java.security.MessageDigest
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension
import db.migration.V16__recoverable_read_indexes
import kotlin.test.*

class ReadIndexMigrationConfigTest {
    @Test fun all_migration_entry_points_share_session_lock_settings() {
        val configuration=DatabaseFactory.migrationConfiguration("jdbc:postgresql://unused.invalid/wishlist","test","test")
        assertEquals(listOf("classpath:db/migration"),configuration.locations.map { it.descriptor })
        assertFalse(configuration.getConfigurationExtension(PostgreSQLConfigurationExtension::class.java).isTransactionalLock)
        val migration=V16__recoverable_read_indexes()
        assertFalse(migration.canExecuteInTransaction());assertEquals("16",migration.version.toString())
        assertNotNull(migration.checksum)
    }
    @Test fun v16_checksum_covers_its_migration_and_executor_sources() {
        val checksum=java.util.zip.CRC32()
        for(path in listOf("db/migration/V16__recoverable_read_indexes.kt","app/persistence/ReadIndexRollout.kt")) {
            checksum.update(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("src/main/kotlin",path)))
        }
        assertEquals(checksum.value.toInt(),V16__recoverable_read_indexes().checksum)
    }
    @Test fun original_v15_bytes_are_preserved_after_publication() {
        val original=javaClass.getResourceAsStream("/db/migration/V15__wishlist_read_indexes.sql")!!.use { it.readBytes() }
        val digest=MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) }
        assertEquals("ffd91c4654ca49d46409646e5fee0a014893e941f978d4693322c15e7e71803a",digest)
        assertNull(javaClass.getResource("/db/migration/V15__wishlist_read_indexes.sql.conf"))
    }
}
