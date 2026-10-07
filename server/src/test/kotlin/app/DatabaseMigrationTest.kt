package app

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import app.testutil.PostgresTestContainer
import java.sql.DriverManager
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import org.flywaydb.core.Flyway
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class DatabaseMigrationTest {
    @Test fun `V10 upgrade adds nullable sharing time without changing V9 item job and outbox snapshots`() {
        PostgresTestContainer().use { database ->
            database.start()
            Flyway.configure().dataSource(database.jdbcUrl, database.username, database.password).target("9").load().migrate()
            database.createConnection("").use { connection ->
                val itemId = UUID.randomUUID()
                val jobId = UUID.randomUUID()
                connection.createStatement().use { s ->
                    s.executeUpdate("""insert into wishlist_items(id,owner_id,client_submission_id,source_url,analysis_status,lifecycle_status,
                        product_name,product_image_url,version,created_at,updated_at) values ('$itemId','${UUID.randomUUID()}','${UUID.randomUUID()}',
                        'https://example.com/item','PARTIAL','ACTIVE','기존 상품','https://example.com/image',7,'2026-10-05T01:00:00Z','2026-10-05T02:00:00Z')""")
                    s.executeUpdate("insert into analysis_jobs(id,wishlist_item_id,generation,stage) values ('$jobId','$itemId',1,'PARTIAL')")
                    s.executeUpdate("insert into outbox_events(id,analysis_job_id,event_type,task_name) values ('${UUID.randomUUID()}','$jobId','GENERAL_ANALYSIS','upgrade-task')")
                }
                val before = listOf("wishlist_items", "analysis_jobs", "outbox_events").associateWith { snapshot(connection, it, emptyList()) }
                DatabaseFactory.migrate(database.jdbcUrl, database.username, database.password)
                before.forEach { (table, records) -> assertEquals(records, snapshot(connection, table, if (table == "wishlist_items") listOf("client_created_at", "custom_category_id") else emptyList())) }
                connection.createStatement().use { s -> s.executeQuery("select client_created_at from wishlist_items").use { r ->
                    assertTrue(r.next()); assertNull(r.getObject(1))
                } }
                Flyway.configure().dataSource(database.jdbcUrl, database.username, database.password).load().validate()
            }
        }
    }

    @Test
    fun `all Flyway migrations apply to an empty postgres database`() {
        PostgresTestContainer().use { database ->
            database.start()

            assertDoesNotThrow {
                DatabaseFactory.migrate(database.jdbcUrl, database.username, database.password)
            }

            DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("select version from flyway_schema_history where success order by installed_rank").use { rows ->
                        val versions = buildList { while (rows.next()) add(rows.getString(1)) }
                        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11"), versions)
                    }
                }
            }
            Flyway.configure().dataSource(database.jdbcUrl, database.username, database.password).load().validate()
        }
    }

    @Test
    fun `upgrade preserves legacy records and backfills state and expired running leases`() {
        PostgresTestContainer().use { database ->
            database.start()
            Flyway.configure().dataSource(database.jdbcUrl, database.username, database.password).target("7").load().migrate()
            database.createConnection("").use { connection ->
                val owner = UUID.randomUUID()
                val ids = (0..7).map { UUID.randomUUID() }
                val statuses = listOf("READY", "PARTIAL", "FAILED_RETRYABLE", "FAILED_TERMINAL", "READY", "PROCESSING", "READY", "PARTIAL")
                val failures = listOf(null, "AI_ABSTAINED", "AI_UNUSABLE_RESPONSE", "AI_INVALID_CANDIDATE", null, null, "AI_USAGE_OUT_OF_RANGE", "ACCESS_DENIED")
                ids.forEachIndexed { index, id ->
                    connection.prepareStatement("""
                        insert into wishlist_items (id, owner_id, client_submission_id, source_url, analysis_status,
                        lifecycle_status, version, created_at, updated_at, product_name, product_image_url,
                        product_description, canonical_url, predicted_category_id, predicted_purpose_id, analysis_failure_code, classified_at)
                        values (?, ?, ?, 'https://example.com/legacy', ?, ?, 7, '2026-09-01T00:00:00Z', '2026-09-02T00:00:00Z', ?, ?,
                        'description', 'https://example.com/canonical', ?, 'legacy-purpose', ?, '2026-09-03T00:00:00Z')
                    """.trimIndent()).use { statement ->
                        statement.setObject(1, id); statement.setObject(2, owner); statement.setObject(3, UUID.randomUUID())
                        statement.setString(4, statuses[index]); statement.setString(5, if (index == 4) "DELETED" else "ACTIVE")
                        statement.setString(6, if (index == 5 || index == 6) null else "legacy name")
                        statement.setString(7, if (index == 5) null else "https://example.com/image")
                        statement.setString(8, if (index == 0 || index == 1 || index == 4) "C026" else null)
                        statement.setString(9, failures[index]); statement.executeUpdate()
                    }
                }
                val general = UUID.randomUUID()
                val browser = UUID.randomUUID()
                val pending = UUID.randomUUID()
                connection.createStatement().use { s ->
                    s.executeUpdate("insert into analysis_jobs(id,wishlist_item_id,generation,stage,attempt_count,pending_product_name,candidate_snapshot_json) values ('$general','${ids[0]}',1,'GENERAL_RUNNING',2,'pending','snapshot')")
                    s.executeUpdate("insert into analysis_jobs(id,wishlist_item_id,generation,stage,browser_attempt_count) values ('$browser','${ids[0]}',4,'BROWSER_RUNNING',1)")
                    s.executeUpdate("insert into analysis_jobs(id,wishlist_item_id,generation,stage) values ('$pending','${ids[1]}',2,'GENERAL_PENDING')")
                    s.executeUpdate("insert into outbox_events(id,analysis_job_id,event_type,task_name) values ('${UUID.randomUUID()}','$general','GENERAL_ANALYSIS','legacy-task')")
                    s.executeUpdate("insert into llm_budget_windows(id,window_type,window_start,reserved_microusd,settled_microusd,ceiling_microusd) values ('${UUID.randomUUID()}','MONTH','2026-09-01T00:00:00Z',100,30,1000)")
                    s.executeUpdate("insert into llm_budget_reservations(id,request_id,analysis_job_id,generation,price_table_version,state,maximum_microusd,actual_microusd,lease_until) values ('${UUID.randomUUID()}','${UUID.randomUUID()}','$general',1,'v1','RESERVED',100,30,'2026-09-04T00:00:00Z')")
                    s.executeUpdate("insert into llm_budget_alerts(id,window_type,window_start,threshold_percent) values ('${UUID.randomUUID()}','MONTH','2026-09-01T00:00:00Z',80)")
                }
                val itemFields = "review_status,manual_completion_at,category_id,category_source,category_missing_reason,purpose_id,purpose_source,name_source,image_source,user_override_fields,current_generation,client_created_at,custom_category_id".split(",")
                val jobFields = listOf("execution_token", "lease_until", "claimed_item_version")
                val queries = mapOf(
                    "wishlist_items" to itemFields, "analysis_jobs" to jobFields, "outbox_events" to emptyList(),
                    "llm_budget_windows" to emptyList(), "llm_budget_reservations" to emptyList(), "llm_budget_alerts" to emptyList(),
                )
                val before = queries.mapValues { (table, fields) -> snapshot(connection, table, fields) }
                DatabaseFactory.migrate(database.jdbcUrl, database.username, database.password)
                queries.forEach { (table, fields) -> assertEquals(before[table], snapshot(connection, table, fields), table) }
                ids.forEachIndexed { index, id ->
                    connection.createStatement().use { s ->
                        s.executeQuery("select * from wishlist_items where id='$id'").use { r ->
                            check(r.next())
                            assertEquals(if (index == 0) 4 else if (index == 1) 2 else 1, r.getInt("current_generation"))
                            assertEquals(if (index == 0 || index == 4) "C026" else null, r.getString("category_id"))
                            assertEquals(if (index == 0 || index == 4) "AI" else null, r.getString("category_source"))
                            val reason = when (index) { 0,4 -> null; 1 -> "AI_ABSTAINED"; 2,3,6 -> "AI_RESPONSE_UNUSABLE"; else -> "EXTRACTION_UNRESOLVED" }
                            assertEquals(reason, r.getString("category_missing_reason"))
                            assertEquals(if (index == 0 || index == 4) "PENDING" else "NOT_REQUIRED", r.getString("review_status"))
                            assertEquals(if (index == 5 || index == 6) null else "AI", r.getString("name_source"))
                            assertEquals(if (index == 5) null else "AI", r.getString("image_source"))
                            assertEquals("UNASSIGNED", r.getString("purpose_source"))
                            assertNull(r.getString("purpose_id")); assertNull(r.getObject("manual_completion_at"))
                            assertEquals(emptyList<String>(), (r.getArray("user_override_fields").array as Array<*>).toList())
                        }
                    }
                }
                connection.createStatement().use { s ->
                    s.executeQuery("select stage, execution_token, claimed_item_version, lease_until <= clock_timestamp() as expired from analysis_jobs").use { r ->
                        while (r.next()) {
                            assertNull(r.getObject("execution_token")); assertNull(r.getObject("claimed_item_version"))
                            if (r.getString("stage").endsWith("RUNNING")) assertTrue(r.getBoolean("expired"))
                            else assertNull(r.getObject("expired"))
                        }
                    }
                    s.executeQuery("select count(*) from pg_indexes where indexname='analysis_jobs_recovery_idx'").use { r -> check(r.next()); assertEquals(1, r.getInt(1)) }
                }
                Flyway.configure().dataSource(database.jdbcUrl, database.username, database.password).load().validate()
                DatabaseFactory.migrate(database.jdbcUrl, database.username, database.password)
            }
        }
    }

    @Test
    fun `state constraints reject invalid combinations and permit explicit purpose clearing`() {
        PostgresTestContainer().use { database ->
            database.start()
            DatabaseFactory.migrate(database.jdbcUrl, database.username, database.password)
            database.createConnection("").use { c ->
                val id = UUID.randomUUID()
                c.createStatement().use { it.executeUpdate("insert into wishlist_items(id,owner_id,client_submission_id,source_url,analysis_status,lifecycle_status) values ('$id','${UUID.randomUUID()}','${UUID.randomUUID()}','https://example.com','PROCESSING','ACTIVE')") }
                val invalid = listOf(
                    "analysis_status='UNKNOWN'", "review_status='UNKNOWN'", "lifecycle_status='UNKNOWN'", "version=0", "current_generation=0",
                    "category_source='UNKNOWN'", "category_missing_reason='UNKNOWN'", "purpose_source='UNKNOWN'", "name_source='UNKNOWN'", "image_source='UNKNOWN'",
                    "category_id='C026',category_source=null,category_missing_reason=null",
                    "category_id='C026',category_source='UNASSIGNED',category_missing_reason=null",
                    "category_id='C026',category_source='AI',category_missing_reason='AI_ABSTAINED'",
                    "purpose_source='AI',purpose_id=null", "user_override_fields=array['PRICE']", "user_override_fields=array[null]::text[]",
                )
                for (assignment in invalid) {
                    val failure = assertFailsWith<SQLException>(assignment) {
                        c.createStatement().use { it.executeUpdate("update wishlist_items set $assignment where id='$id'") }
                    }
                    assertEquals("23514", failure.sqlState, assignment)
                }
                c.createStatement().use { it.executeUpdate("update wishlist_items set category_id='C026',category_source='USER',category_missing_reason=null,purpose_source='USER',purpose_id=null,user_override_fields=array['CATEGORY','PURPOSE'] where id='$id'") }
                assertFailsWith<SQLException> {
                    c.createStatement().use { it.executeUpdate("insert into analysis_jobs(id,wishlist_item_id,generation,stage) values ('${UUID.randomUUID()}','$id',0,'GENERAL_PENDING')") }
                }
            }
        }
    }

    private fun snapshot(connection: Connection, table: String, ignored: List<String>): List<String> {
        val fields = ignored.joinToString(",") { "'$it'" }
        return connection.createStatement().use { s ->
            s.executeQuery("select (to_jsonb(t) - array[$fields]::text[])::text from $table t order by id").use { r ->
                buildList { while (r.next()) add(r.getString(1)) }
            }
        }
    }
}
