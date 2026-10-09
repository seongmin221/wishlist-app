package app.wishlist.shared.data.local

import app.cash.sqldelight.db.use
import kotlin.test.Test
import kotlin.test.assertEquals

/** C2 shipped v1 only to development devices; v1 DDL is copied verbatim from that Wishlist.sq. */
private const val V1_LOCAL_SUBMISSION_DDL = """
CREATE TABLE local_submission (
    client_submission_id TEXT NOT NULL PRIMARY KEY,
    source_url TEXT NOT NULL,
    created_at TEXT NOT NULL,
    account_binding TEXT,
    status TEXT NOT NULL,
    server_item_id TEXT,
    error_kind TEXT,
    error_code TEXT,
    error_request_id TEXT,
    error_current_version INTEGER,
    error_retry_after_seconds INTEGER
)"""

private const val V1_ITEM_CACHE_DDL = """
CREATE TABLE item_cache (
    account_id TEXT NOT NULL,
    item_id TEXT NOT NULL,
    version INTEGER NOT NULL,
    client_submission_id TEXT NOT NULL,
    source_url TEXT NOT NULL,
    product_name TEXT,
    product_image_url TEXT,
    product_price TEXT,
    product_currency TEXT,
    product_brand TEXT,
    product_merchant TEXT,
    product_metadata_checked_at TEXT,
    product_name_source TEXT,
    product_image_source TEXT,
    category_id TEXT,
    category_source TEXT,
    category_missing_reason TEXT,
    category_name TEXT,
    category_parent_id TEXT,
    category_kind TEXT,
    purpose_id TEXT,
    purpose_source TEXT NOT NULL,
    analysis_status TEXT NOT NULL,
    analysis_failure_code TEXT,
    review_status TEXT NOT NULL,
    lifecycle_status TEXT NOT NULL,
    required_action TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    manual_completion_at TEXT,
    allowed_actions TEXT NOT NULL,
    client_created_at TEXT,
    PRIMARY KEY (account_id, item_id)
)"""

private const val V2_LOCAL_SUBMISSION_DDL = """
CREATE TABLE local_submission (
    client_submission_id TEXT NOT NULL PRIMARY KEY, source_url TEXT NOT NULL, shared_at_us INTEGER NOT NULL,
    account_binding TEXT, status TEXT NOT NULL, retry_after_us INTEGER, error_kind TEXT, error_code TEXT,
    error_request_id TEXT, error_current_version INTEGER, error_retry_after_seconds INTEGER
)"""

class SchemaMigrationTest {
    @Test fun migratesV2ItemRowsToV3WithNullPurposeDisplay() {
        val path = newTestDbPath()
        openRawDriver(path).use { raw ->
            raw.execute(null, V2_LOCAL_SUBMISSION_DDL, 0)
            raw.execute(null, "CREATE TABLE app_state (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)", 0)
            raw.execute(null, V1_ITEM_CACHE_DDL, 0)
            raw.execute(
                null,
                """INSERT INTO item_cache VALUES ('A','$itemId',4,'$UUID_A','https://shop.example/x','Name',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,
                    'C026','USER',NULL,'음향','G01','PUBLIC','P1','USER','READY',NULL,'PENDING','ACTIVE','NONE',
                    '2026-10-07T00:00:00Z','2026-10-07T00:00:01Z',NULL,'EDIT,DELETE','2026-10-06T23:59:59Z')""",
                0,
            )
            raw.execute(null, "PRAGMA user_version = 2", 0)
        }
        openTestDriver(path).use { driver ->
            val row = WishlistDatabase(driver).wishlistQueries.selectItem("A", itemId).executeAsOne()
            assertEquals(listOf<String?>(null, null, null), listOf(row.purpose_name, row.purpose_color_key, row.purpose_icon_key))
            assertEquals("P1", row.purpose_id)
            assertEquals("USER", row.purpose_source)
            assertEquals("Name", row.product_name)
            assertEquals(4L, row.version)
            assertEquals("2026-10-06T23:59:59Z", row.client_created_at)
        }
        deleteTestDb(path)
    }

    @Test fun migratesV1AllTheWayToV3() {
        val path = newTestDbPath()
        openRawDriver(path).use { raw ->
            raw.execute(null, V1_LOCAL_SUBMISSION_DDL, 0)
            raw.execute(null, V1_ITEM_CACHE_DDL, 0)
            raw.execute(null, "PRAGMA user_version = 1", 0)
        }
        openTestDriver(path).use { driver ->
            val columns = mutableListOf<String>()
            driver.executeQuery(null, "PRAGMA table_info(item_cache)", { c ->
                while (c.next().value) columns += c.getString(1)!!
                app.cash.sqldelight.db.QueryResult.Unit
            }, 0)
            assertEquals(listOf("purpose_name", "purpose_color_key", "purpose_icon_key"), columns.takeLast(3))
            assertEquals(3L, WishlistDatabase.Schema.version)
        }
        deleteTestDb(path)
    }

    @Test fun migratesV1RowsWithMillisecondOrder() {
        val path = newTestDbPath()
        openRawDriver(path).use { raw ->
            raw.execute(null, V1_LOCAL_SUBMISSION_DDL, 0)
            raw.execute(null, V1_ITEM_CACHE_DDL, 0)
            raw.execute(null, "INSERT INTO local_submission VALUES ('$UUID_A','https://a.example','2026-10-07T00:00:00.500Z',NULL,'PENDING',NULL,NULL,NULL,NULL,NULL,NULL)", 0)
            raw.execute(null, "INSERT INTO local_submission VALUES ('$UUID_B','https://b.example','2026-10-07T00:00:00Z','A','ACCEPTED',NULL,NULL,NULL,NULL,NULL,NULL)", 0)
            raw.execute(null, "PRAGMA user_version = 1", 0)
        }
        openTestDriver(path).use { driver ->
            val rows = WishlistDatabase(driver).wishlistQueries.selectUnboundSubmissions().executeAsList()
            // B is bound to A so it is not unbound; A keeps its 500 ms as microseconds.
            assertEquals(1_791_331_200_500_000L, rows.single().shared_at_us)
            val b = WishlistDatabase(driver).wishlistQueries.selectSubmission(UUID_B).executeAsOne()
            assertEquals("PENDING", b.status) // ACCEPTED recovers safely by re-sending the same key
            assertEquals(1_791_331_200_000_000L, b.shared_at_us)
        }
        deleteTestDb(path)
    }

    @Test fun migratedV1SubmittingRowKeepsErrorAndGetsAppStateTable() {
        val path = newTestDbPath()
        openRawDriver(path).use { raw ->
            raw.execute(null, V1_LOCAL_SUBMISSION_DDL, 0)
            raw.execute(null, V1_ITEM_CACHE_DDL, 0)
            raw.execute(null, "INSERT INTO local_submission VALUES ('$UUID_C','https://c.example','2026-10-07T00:00:01.250Z','A','SUBMITTING','srv-1','NETWORK','N1','req-1',3,30)", 0)
            raw.execute(null, "PRAGMA user_version = 1", 0)
        }
        openTestDriver(path).use { driver ->
            val queries = WishlistDatabase(driver).wishlistQueries
            val c = queries.selectSubmission(UUID_C).executeAsOne()
            assertEquals("PENDING", c.status)
            assertEquals(1_791_331_201_250_000L, c.shared_at_us)
            assertEquals(listOf("NETWORK", "N1", "req-1"), listOf(c.error_kind, c.error_code, c.error_request_id))
            assertEquals(3L to 30L, c.error_current_version to c.error_retry_after_seconds)
            assertEquals(null, c.retry_after_us)
            queries.upsertAppState("k", "v")
            assertEquals("v", queries.selectAppState("k").executeAsOne())
        }
        deleteTestDb(path)
    }
}
