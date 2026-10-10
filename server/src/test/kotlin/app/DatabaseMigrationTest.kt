package app

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import app.testutil.PostgresTestContainer
import java.sql.DriverManager
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class DatabaseMigrationTest {
    @Test fun `V10 upgrade adds nullable sharing time without changing V9 item job and outbox snapshots`() {
        PostgresTestContainer().use { database ->
            database.start()
            DatabaseFactory.migrationConfiguration(database.jdbcUrl, database.username, database.password).target("9").load().migrate()
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
                before.forEach { (table, records) -> assertEquals(records, snapshot(connection, table, (if (table == "wishlist_items") listOf("client_created_at", "custom_category_id", "legacy_purpose_id") else if (table == "analysis_jobs") listOf("pending_purpose_judged") else emptyList()) + V17_COLUMNS.getValue(table))) }
                connection.createStatement().use { s -> s.executeQuery("select client_created_at from wishlist_items").use { r ->
                    assertTrue(r.next()); assertNull(r.getObject(1))
                } }
                DatabaseFactory.migrationConfiguration(database.jdbcUrl, database.username, database.password).load().validate()
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
                        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17"), versions)
                    }
                }
            }
            DatabaseFactory.migrationConfiguration(database.jdbcUrl, database.username, database.password).load().validate()
        }
    }

    @Test
    fun `upgrade preserves legacy records and backfills state and expired running leases`() {
        PostgresTestContainer().use { database ->
            database.start()
            DatabaseFactory.migrationConfiguration(database.jdbcUrl, database.username, database.password).target("7").load().migrate()
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
                val itemFields = "review_status,manual_completion_at,category_id,category_source,category_missing_reason,purpose_id,purpose_source,name_source,image_source,user_override_fields,current_generation,client_created_at,custom_category_id,legacy_purpose_id".split(",")
                val jobFields = listOf("execution_token", "lease_until", "claimed_item_version", "pending_purpose_judged")
                val queries = mapOf(
                    "wishlist_items" to itemFields + V17_COLUMNS.getValue("wishlist_items"), "analysis_jobs" to jobFields + V17_COLUMNS.getValue("analysis_jobs"),
                    "outbox_events" to V17_COLUMNS.getValue("outbox_events"),
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
                DatabaseFactory.migrationConfiguration(database.jdbcUrl, database.username, database.password).load().validate()
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

    @Test fun `V13 upgrade preserves legacy purpose strings and validates owner purpose references`() {
        PostgresTestContainer().use { database ->
            database.start()
            DatabaseFactory.migrationConfiguration(database.jdbcUrl, database.username, database.password).target("12").load().migrate()
            database.createConnection("").use { connection ->
                val owner = UUID.randomUUID()
                val ids = List(4) { UUID.randomUUID() }
                // The 4th row was PENDING only because of its AI purpose (category is USER); nothing remains to review.
                val rows = listOf(Triple("legacy-ai", "AI", "PENDING"), Triple("legacy-user", "USER", "CONFIRMED"), Triple(null, "UNASSIGNED", "NOT_REQUIRED"),
                    Triple("legacy-ai-only", "AI", "PENDING"))
                connection.createStatement().use { it.executeUpdate("insert into app_users(id) values ('$owner')") }
                ids.zip(rows).forEach { (id, row) ->
                    connection.createStatement().use { it.executeUpdate("""insert into wishlist_items(id,owner_id,client_submission_id,source_url,analysis_status,lifecycle_status,
                        product_name,category_id,category_source,category_missing_reason,purpose_id,purpose_source,review_status)
                        values ('$id','$owner','${UUID.randomUUID()}','https://example.com/item','READY','ACTIVE','name','C026','${if (row.first == "legacy-ai-only") "USER" else "AI"}',null,
                        ${row.first?.let { "'$it'" } ?: "null"},'${row.second}','${row.third}')""") }
                }
                DatabaseFactory.migrate(database.jdbcUrl, database.username, database.password)
                ids.zip(rows).forEach { (id, row) ->
                    connection.createStatement().use { s -> s.executeQuery("select purpose_id,legacy_purpose_id,purpose_source,review_status from wishlist_items where id='$id'").use { r ->
                        check(r.next())
                        assertNull(r.getObject("purpose_id"))
                        assertEquals(row.first, r.getString("legacy_purpose_id"))
                        assertEquals(if (row.second == "AI") "UNASSIGNED" else row.second, r.getString("purpose_source"))
                        assertEquals(if (row.first == "legacy-ai-only") "NOT_REQUIRED" else row.third, r.getString("review_status"))
                    } }
                }
                connection.createStatement().use { s ->
                    s.executeQuery("select convalidated from pg_constraint where conname='wishlist_purpose_owner_fk'").use { r -> check(r.next()); assertTrue(r.getBoolean(1)) }
                    s.executeQuery("select data_type from information_schema.columns where table_name='wishlist_items' and column_name='purpose_id'").use { r -> check(r.next()); assertEquals("uuid", r.getString(1)) }
                    val failure = assertFailsWith<SQLException> {
                        s.executeUpdate("insert into mutation_receipts(owner_id,operation,idempotency_key,request_fingerprint) values ('$owner','CREATE_PURPOSE','${UUID.randomUUID()}','${"0".repeat(64)}')")
                    }
                    assertEquals("23514", failure.sqlState)
                }
                DatabaseFactory.migrationConfiguration(database.jdbcUrl, database.username, database.password).load().validate()
            }
        }
    }

    @Test fun `V14 raises ceilings of existing default windows so current windows keep reserving`() {
        PostgresTestContainer().use { database ->
            database.start()
            DatabaseFactory.migrationConfiguration(database.jdbcUrl, database.username, database.password).target("13").load().migrate()
            database.createConnection("").use { c -> c.createStatement().use { s ->
                s.executeUpdate("""insert into llm_budget_windows(id,window_type,window_start,reserved_microusd,settled_microusd,ceiling_microusd) values
                    ('${UUID.randomUUID()}','DAILY',date_trunc('day',clock_timestamp() at time zone 'UTC') at time zone 'UTC',0,599500,600000),
                    ('${UUID.randomUUID()}','MONTHLY',date_trunc('month',clock_timestamp() at time zone 'UTC') at time zone 'UTC',0,0,6000000),
                    ('${UUID.randomUUID()}','MONTH','2026-09-01T00:00:00Z',0,0,1000)""")
            } }
            DatabaseFactory.migrate(database.jdbcUrl, database.username, database.password)
            database.createConnection("").use { c -> c.createStatement().use { s ->
                s.executeQuery("select window_type,ceiling_microusd from llm_budget_windows order by window_type").use { r ->
                    val rows = buildMap { while (r.next()) put(r.getString(1), r.getLong(2)) }
                    assertEquals(mapOf("DAILY" to 721_000L, "MONTH" to 1000L, "MONTHLY" to 7_210_000L), rows)
                }
            } }
            val source = DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password)
            val claim = app.testutil.newAnalysisClaim(source)
            assertTrue(app.budget.LlmBudgetService(source).reserveBeforeCall(claim, UUID.randomUUID()) is app.budget.ReserveResult.Reserved)
        }
    }

    @Test fun `V17 upgrade keeps V16 rows and adds nullable metadata and recovery defaults`() {
        PostgresTestContainer().use { database ->
            database.start()
            DatabaseFactory.migrationConfiguration(database.jdbcUrl, database.username, database.password).target("16").load().migrate()
            database.createConnection("").use { connection ->
                val itemId = UUID.randomUUID()
                val jobId = UUID.randomUUID()
                connection.createStatement().use { s ->
                    s.executeUpdate("""insert into wishlist_items(id,owner_id,client_submission_id,source_url,analysis_status,lifecycle_status,
                        product_name,version,created_at,updated_at) values ('$itemId','${UUID.randomUUID()}','${UUID.randomUUID()}',
                        'https://example.com/item','PROCESSING','ACTIVE','기존 상품',3,'2026-10-09T01:00:00Z','2026-10-09T02:00:00Z')""")
                    s.executeUpdate("insert into analysis_jobs(id,wishlist_item_id,generation,stage,attempt_count) values ('$jobId','$itemId',1,'GENERAL_PENDING',1)")
                    s.executeUpdate("insert into outbox_events(id,analysis_job_id,event_type,task_name) values ('${UUID.randomUUID()}','$jobId','GENERAL_ANALYSIS','v17-upgrade-task')")
                }
                val before = listOf("wishlist_items", "analysis_jobs", "outbox_events").associateWith { snapshot(connection, it, emptyList()) }
                DatabaseFactory.migrate(database.jdbcUrl, database.username, database.password)
                before.forEach { (table, records) -> assertEquals(records, snapshot(connection, table, V17_COLUMNS.getValue(table))) }
                connection.createStatement().use { s ->
                    s.executeQuery("""select product_brand,product_price,product_currency,merchant_name,metadata_checked_at from wishlist_items""").use { r ->
                        assertTrue(r.next()); for (i in 1..5) assertNull(r.getObject(i))
                    }
                    s.executeQuery("""select pending_brand,pending_price,pending_currency,pending_merchant,recovery_check_at,recovery_seq from analysis_jobs""").use { r ->
                        assertTrue(r.next()); for (i in 1..5) assertNull(r.getObject(i)); assertEquals(0, r.getInt(6))
                    }
                    s.executeQuery("select not_before from outbox_events").use { r -> assertTrue(r.next()); assertNull(r.getObject(1)) }
                    s.executeQuery("""select count(*) from pg_indexes where indexname in
                        ('analysis_jobs_pending_recovery_idx','analysis_jobs_running_recovery_idx','outbox_events_job_created_idx')""").use { r ->
                        assertTrue(r.next()); assertEquals(3, r.getInt(1))
                    }
                }
                DatabaseFactory.migrationConfiguration(database.jdbcUrl, database.username, database.password).load().validate()
            }
        }
    }

    @Test fun `V17 constraints reject half price pairs and invalid currency`() {
        PostgresTestContainer().use { database ->
            database.start()
            DatabaseFactory.migrate(database.jdbcUrl, database.username, database.password)
            val source = DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password)
            val claim = app.testutil.newAnalysisClaim(source)
            val item = claim.itemId
            val job = claim.jobId
            source.connection.use { c ->
                for (sql in listOf(
                    "update wishlist_items set product_price=1000 where id='$item'",
                    "update wishlist_items set product_currency='KRW' where id='$item'",
                    "update wishlist_items set product_price=-1,product_currency='KRW' where id='$item'",
                    "update wishlist_items set product_price=1,product_currency='krw' where id='$item'",
                    "update analysis_jobs set pending_price=1 where id='$job'",
                    "update analysis_jobs set recovery_seq=-1 where id='$job'",
                )) assertFailsWith<SQLException>(sql) { c.createStatement().use { it.executeUpdate(sql) } }
                c.createStatement().use { s ->
                    assertEquals(1, s.executeUpdate("update wishlist_items set product_price=12900.5,product_currency='KRW' where id='$item'"))
                    assertEquals(1, s.executeUpdate("update analysis_jobs set pending_price=1,pending_currency='USD' where id='$job'"))
                }
            }
        }
    }

    private companion object {
        val V17_COLUMNS = mapOf(
            "wishlist_items" to listOf("product_brand", "product_price", "product_currency", "merchant_name", "metadata_checked_at"),
            "analysis_jobs" to listOf("pending_brand", "pending_price", "pending_currency", "pending_merchant", "recovery_seq", "recovery_check_at"),
            "outbox_events" to listOf("not_before"),
        )
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
