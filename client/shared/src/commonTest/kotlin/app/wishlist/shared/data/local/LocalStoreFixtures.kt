package app.wishlist.shared.data.local

import app.cash.sqldelight.db.SqlDriver
import app.wishlist.shared.core.MutableAuthSession
import app.wishlist.shared.model.*
import kotlin.time.Instant

internal const val itemId = "00000000-0000-0000-0000-0000000000a1"
internal const val submissionId = "00000000-0000-0000-0000-0000000000b1"

internal fun item(version: Int = 1, id: String = itemId, name: String? = "헤드폰") = WishlistItem(
    id = id,
    clientSubmissionId = submissionId,
    version = version,
    sourceUrl = " https://shop.example/item?x=1 ",
    product = ProductSnapshot(
        name = name, imageUrl = "https://img.example/1.png", price = DecimalAmount.parseOrNull("12345678901234567890.120"),
        currency = "KRW", brand = "브랜드", merchant = "상점",
        metadataCheckedAt = Instant.parse("2026-10-07T01:02:03.123456789Z"),
        nameSource = ValueSource.AI, imageSource = ValueSource.UNKNOWN,
    ),
    category = ItemCategory(id = "C026", source = ValueSource.USER, name = "음향", parentId = "G01", kind = "PUBLIC"),
    purpose = ItemPurpose("P1", ValueSource.USER),
    analysis = ItemAnalysis(AnalysisStatus.PARTIAL, "F1"),
    reviewStatus = ReviewStatus.PENDING,
    lifecycleStatus = LifecycleStatus.ACTIVE,
    requiredAction = RequiredAction.CLASSIFICATION_REVIEW,
    createdAt = Instant.parse("2026-10-07T00:00:00Z"),
    updatedAt = Instant.parse("2026-10-07T00:00:01.5Z"),
    manualCompletionAt = Instant.parse("2026-10-08T00:00:00Z"),
    allowedActions = setOf(ItemAction.EDIT, ItemAction.REVIEW),
    clientCreatedAt = Instant.parse("2026-10-06T23:59:59Z"),
)

internal fun submission(
    id: String = submissionId,
    binding: String? = null,
    status: SubmissionStatus = SubmissionStatus.PENDING,
) = LocalSubmission(
    clientSubmissionId = id, sourceUrl = " https://shop.example/item?x=1 ",
    createdAt = Instant.parse("2026-10-07T00:00:00Z"), accountBinding = binding, submissionStatus = status,
)

/** File-backed store whose driver can be closed and reopened; always [close] it. */
internal class StoreHarness(val session: MutableAuthSession = MutableAuthSession(), private val hook: () -> Unit = {}) {
    val path = newTestDbPath()
    private var driver: SqlDriver = openTestDriver(path)
    var store = SqlLocalStore(session, driver, hook)
        private set

    fun reopen() {
        driver.close()
        driver = openTestDriver(path)
        store = SqlLocalStore(session, driver, hook)
    }

    fun close() {
        driver.close()
        deleteTestDb(path)
    }
}

internal inline fun withHarness(
    session: MutableAuthSession = MutableAuthSession(),
    noinline hook: () -> Unit = {},
    block: (StoreHarness) -> Unit,
) {
    val harness = StoreHarness(session, hook)
    try { block(harness) } finally { harness.close() }
}
