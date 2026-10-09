package app.wishlist

import app.DatabaseFactory
import app.DatabaseMigrationJob
import app.testutil.*
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class ReadIndexRecoveryTest {
    @Test fun v16_recovers_invalid_index_without_rebuilding_valid_v15_indexes() = PostgresTestContainer().use { db ->
        db.start()
        val configuration=DatabaseFactory.migrationConfiguration(db.jdbcUrl,db.username,db.password)
        configuration.target("15").load().migrate()
        val source=DatabaseFactory.dataSource(db.jdbcUrl,db.username,db.password)
        val owner=UUID.randomUUID();val time=Instant.parse("2026-10-07T10:00:00Z")
        val state=WishlistItemState(AnalysisStatus.READY,ReviewStatus.CONFIRMED,LifecycleStatus.ACTIVE,"name","C026",null,null)
        source.connection.use { insertReadFixtures(it,owner,(0..1).map { ReadFixture(ReadPosition(time,UUID.randomUUID()),state) }) }
        source.connection.use { c ->
            val validOid=c.createStatement().use { s -> s.executeQuery("select 'wishlist_active_public_category_order'::regclass::oid").use { it.next();it.getLong(1) } }
            c.createStatement().use { s -> s.execute("drop index concurrently wishlist_active_owner_order") }
            assertFailsWith<SQLException> { c.createStatement().use { s -> s.execute("create unique index concurrently wishlist_active_owner_order on wishlist_items(owner_id)") } }
            c.createStatement().use { s -> s.executeQuery("select indisvalid from pg_index where indexrelid='wishlist_active_owner_order'::regclass").use { it.next();assertFalse(it.getBoolean(1)) } }
            DatabaseFactory.migrate(db.jdbcUrl,db.username,db.password)
            DatabaseFactory.migrationConfiguration(db.jdbcUrl,db.username,db.password).load().validate()
            c.createStatement().use { s ->
                s.executeQuery("select 'wishlist_active_public_category_order'::regclass::oid").use { it.next();assertEquals(validOid,it.getLong(1)) }
                s.executeQuery("select count(*) from pg_index where indexrelid in ('wishlist_active_public_category_order'::regclass,'wishlist_active_custom_category_order'::regclass,'wishlist_active_owner_order'::regclass) and indisvalid and indisready and indislive").use { it.next();assertEquals(3,it.getInt(1)) }
            }
        }
    }
    @Test fun deployment_job_retries_failed_v16_without_manual_repair() = PostgresTestContainer().use { db ->
        db.start()
        DatabaseFactory.migrationConfiguration(db.jdbcUrl,db.username,db.password).target("15").load().migrate()
        val source=DatabaseFactory.dataSource(db.jdbcUrl,db.username,db.password)
        source.connection.use { c -> c.createStatement().use { s ->
            s.execute("create table unrelated_index_owner(id uuid)")
            s.execute("drop index wishlist_active_public_category_order")
            s.execute("create index wishlist_active_public_category_order on unrelated_index_owner(id)")
        } }
        assertFailsWith<org.flywaydb.core.api.FlywayException> { DatabaseFactory.migrate(db.jdbcUrl,db.username,db.password) }
        val validation=DatabaseFactory.migrationConfiguration(db.jdbcUrl,db.username,db.password)
            .ignoreMigrationPatterns("*:pending").load().validateWithResult()
        assertTrue(DatabaseMigrationJob.canRetryV16(validation,DatabaseFactory.migrationConfiguration(db.jdbcUrl,db.username,db.password).load().info().all()))
        source.connection.use { c -> c.createStatement().use { it.execute("drop index wishlist_active_public_category_order") } }
        DatabaseMigrationJob.run(db.jdbcUrl,db.username,db.password)
        DatabaseFactory.migrationConfiguration(db.jdbcUrl,db.username,db.password).load().validate()
        source.connection.use { c -> c.createStatement().use { s ->
            s.executeQuery("select count(*) from flyway_schema_history where version='16' and success").use { r ->
                assertTrue(r.next());assertEquals(1,r.getInt(1))
            }
            s.executeQuery("select count(*) from flyway_schema_history where not success").use { r ->
                assertTrue(r.next());assertEquals(0,r.getInt(1))
            }
        } }
    }

}
