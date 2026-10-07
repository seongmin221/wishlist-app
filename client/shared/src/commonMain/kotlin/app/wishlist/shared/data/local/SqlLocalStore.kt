@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.wishlist.shared.data.local

import app.wishlist.shared.core.*
import app.wishlist.shared.di.RUNTIME_NOT_READY
import app.wishlist.shared.model.*
import app.wishlist.shared.repository.LocalStore
import kotlinx.coroutines.CancellationException
import kotlin.concurrent.Volatile
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Keeps the owner's resources (the SQL driver) open while one store operation runs. It is a
 * counter, never a lock: [enter] does not block, so it adds no lock-order edge.
 */
internal interface StoreLease {
    /** False once the owner is closing: the operation must not touch the DB at all. */
    fun enter(): Boolean

    /** Ends the operation; the last exit after close may run the owner's teardown on this thread. */
    fun exit()

    /** For a store whose driver nobody closes underneath it (unit tests). */
    object None : StoreLease {
        override fun enter() = true
        override fun exit() = Unit
    }
}

/**
 * Single-process SQLite store. Order is lease -> (first driver open) -> session gate -> DB
 * transaction; only the short DB commit runs inside the gate. Every operation holds a [lease] from
 * before the driver is touched until after the gate is left, so the runtime's close never closes
 * the driver under a running query; an operation started after close is UNAVAILABLE/RUNTIME_NOT_READY
 * without touching the DB. The account is always taken from the validated snapshot, never
 * from the caller's arbitrary value. The driver is opened by [LazyDriver] on first use, before the
 * gate is entered. Any non-cancellation exception (including a failed open) becomes
 * LOCAL_STORE_FAILURE.
 */
internal class SqlLocalStore(
    private val session: AuthSession,
    private val driver: LazyDriver,
    /** Test-only seam: runs inside the accept transaction after the cache write. */
    private val afterAcceptCacheWrite: () -> Unit = {},
    private val lease: StoreLease = StoreLease.None,
) : LocalStore {
    @Volatile private var database: WishlistDatabase? = null

    private suspend fun db(): WishlistDatabase = database ?: WishlistDatabase(driver.get()).also { database = it }

    private fun <T> failure(kind: ErrorKind, code: String? = null): ClientResult<T> =
        ClientResult.Failure(ClientError(kind, code))

    /**
     * The only way to [db]: holds the lease across the driver open, the session gate and the
     * blocking SQL, and releases it (also on cancellation) only after the gate has been left.
     */
    private suspend inline fun <T> leased(block: () -> ClientResult<T>): ClientResult<T> {
        if (!lease.enter()) return failure(ErrorKind.UNAVAILABLE, RUNTIME_NOT_READY)
        try {
            return try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure(ErrorKind.UNAVAILABLE, LOCAL_STORE_FAILURE)
            }
        } finally {
            lease.exit()
        }
    }

    /** Device-level work that does not depend on the account (no session gate). */
    private suspend fun <T> local(block: (WishlistDatabase) -> ClientResult<T>): ClientResult<T> =
        leased { block(db()) }

    private suspend fun <T> gated(
        snapshot: SessionSnapshot,
        block: (WishlistDatabase) -> ClientResult<T>,
    ): ClientResult<T> = leased {
        val db = db()
        session.withCurrent(snapshot) { block(db) }
    }

    private suspend fun <T> gatedForAccount(
        snapshot: SessionSnapshot,
        block: (db: WishlistDatabase, accountId: String) -> ClientResult<T>,
    ): ClientResult<T> = gated(snapshot) { db ->
        val accountId = snapshot.accountId ?: return@gated failure(ErrorKind.UNAUTHENTICATED)
        block(db, accountId)
    }

    override suspend fun saveSubmission(submission: LocalSubmission): ClientResult<Unit> {
        val snapshot = session.state.value
        return gated(snapshot) { db ->
            val binding = submission.accountBinding
            if (binding != null && binding != snapshot.accountId) {
                failure(ErrorKind.VALIDATION, ACCOUNT_BINDING_MISMATCH)
            } else {
                insertGuarded(db, submission) { existing ->
                    // An existing binding is never rebound or cleared by another writer.
                    if (existing.account_binding != null && existing.account_binding != binding) {
                        failure(ErrorKind.VALIDATION, ACCOUNT_BINDING_MISMATCH)
                    } else {
                        ClientResult.Success(Unit)
                    }
                }
            }
        }
    }

    override suspend fun importSubmission(submission: LocalSubmission): ClientResult<Unit> =
        local { db -> insertGuarded(db, submission) { ClientResult.Success(Unit) } }

    /**
     * Key guard: a new key is inserted; the same key with another URL is a reused key; the same
     * key and URL keeps the existing row untouched and answers [sameUrl].
     */
    private fun insertGuarded(
        db: WishlistDatabase,
        submission: LocalSubmission,
        sameUrl: (Local_submission) -> ClientResult<Unit>,
    ): ClientResult<Unit> = db.transactionWithResult {
        val queries = db.wishlistQueries
        val existing = queries.selectSubmission(submission.clientSubmissionId).executeAsOneOrNull()
        when {
            existing == null -> {
                queries.insertSubmissionIfAbsent(submission.toRow())
                ClientResult.Success(Unit)
            }
            existing.source_url != submission.sourceUrl -> failure(ErrorKind.CONFLICT, SUBMISSION_KEY_REUSED)
            else -> sameUrl(existing)
        }
    }

    override suspend fun pending(): ClientResult<List<LocalSubmission>> {
        val snapshot = session.state.value
        return gated(snapshot) { db ->
            val account = snapshot.accountId
            val queries = db.wishlistQueries
            val rows = if (account == null) queries.selectUnboundSubmissions().executeAsList()
            else queries.selectVisibleSubmissions(account).executeAsList()
            ClientResult.Success(rows.map { it.toModel() })
        }
    }

    override suspend fun prepareFlush(snapshot: SessionSnapshot): ClientResult<List<LocalSubmission>> =
        gatedForAccount(snapshot) { db, account ->
            db.transactionWithResult {
                val queries = db.wishlistQueries
                queries.resetSubmitting(account)
                queries.bindUnbound(account)
                ClientResult.Success(queries.selectBoundSubmissions(account).executeAsList().map { it.toModel() })
            }
        }

    override suspend fun markSubmission(
        snapshot: SessionSnapshot,
        id: String,
        status: SubmissionStatus,
        error: ClientError?,
        retryAfter: Instant?,
    ): ClientResult<Unit> = gatedForAccount(snapshot) { db, account ->
        db.transactionWithResult {
            val queries = db.wishlistQueries
            val existing = queries.selectSubmission(id).executeAsOneOrNull()
                ?: return@transactionWithResult failure(ErrorKind.NOT_FOUND, SUBMISSION_NOT_FOUND)
            if (existing.account_binding != account) {
                return@transactionWithResult failure(ErrorKind.VALIDATION, ACCOUNT_BINDING_MISMATCH)
            }
            queries.updateSubmissionState(
                status = status.name, retry_after_us = retryAfter?.toEpochMicros(),
                error_kind = error?.kind?.name, error_code = error?.code, error_request_id = error?.requestId,
                error_current_version = error?.currentVersion?.toLong(),
                error_retry_after_seconds = error?.retryAfterSeconds,
                client_submission_id = id, account_binding = account,
            )
            ClientResult.Success(Unit)
        }
    }

    override suspend fun processingItems(snapshot: SessionSnapshot): ClientResult<List<WishlistItem>> =
        gatedForAccount(snapshot) { db, account ->
            ClientResult.Success(db.wishlistQueries.selectProcessingItems(account).executeAsList().map { it.toModel() })
        }

    override suspend fun upsertItem(snapshot: SessionSnapshot, item: WishlistItem): ClientResult<Unit> =
        gatedForAccount(snapshot) { db, account ->
            db.transaction { db.upsertIfNewer(account, item) }
            ClientResult.Success(Unit)
        }

    override suspend fun cachedItem(snapshot: SessionSnapshot, id: String): ClientResult<WishlistItem?> =
        gatedForAccount(snapshot) { db, account ->
            ClientResult.Success(db.wishlistQueries.selectItem(account, id).executeAsOneOrNull()?.toModel())
        }

    override suspend fun accept(snapshot: SessionSnapshot, submissionId: String, item: WishlistItem): ClientResult<Unit> =
        gatedForAccount(snapshot) { db, account ->
            if (!sameUuid(item.clientSubmissionId, submissionId)) {
                return@gatedForAccount failure(ErrorKind.VALIDATION, SUBMISSION_ITEM_MISMATCH)
            }
            db.transactionWithResult {
                val queries = db.wishlistQueries
                val pending = queries.selectSubmission(submissionId).executeAsOneOrNull()
                    ?: return@transactionWithResult failure(ErrorKind.NOT_FOUND, SUBMISSION_NOT_FOUND)
                if (pending.account_binding != account) {
                    return@transactionWithResult failure(ErrorKind.VALIDATION, ACCOUNT_BINDING_MISMATCH)
                }
                if (item.lifecycleStatus == LifecycleStatus.DELETED) {
                    queries.deleteItemThrough(account, item.id, item.version.toLong())
                } else {
                    db.upsertIfNewer(account, item)
                }
                afterAcceptCacheWrite()
                queries.deleteSubmission(submissionId)
                ClientResult.Success(Unit)
            }
        }

    override suspend fun removeCachedItem(snapshot: SessionSnapshot, id: String, throughVersion: Int): ClientResult<Unit> =
        gatedForAccount(snapshot) { db, account ->
            db.wishlistQueries.deleteItemThrough(account, id, throughVersion.toLong())
            ClientResult.Success(Unit)
        }

    override suspend fun clearCurrentCache(): ClientResult<Unit> {
        val snapshot = session.state.value
        return gated(snapshot) { db ->
            snapshot.accountId?.let { db.wishlistQueries.deleteAccountItems(it) }
            ClientResult.Success(Unit)
        }
    }

    override suspend fun readAppState(key: String): ClientResult<String?> =
        local { db -> ClientResult.Success(db.wishlistQueries.selectAppState(key).executeAsOneOrNull()) }

    override suspend fun writeAppState(key: String, value: String?): ClientResult<Unit> = local { db ->
        if (value == null) db.wishlistQueries.deleteAppState(key) else db.wishlistQueries.upsertAppState(key, value)
        ClientResult.Success(Unit)
    }

    private fun WishlistDatabase.upsertIfNewer(account: String, item: WishlistItem) {
        val existing = wishlistQueries.selectItem(account, item.id).executeAsOneOrNull()?.version
        if (existing == null || item.version > existing) wishlistQueries.insertItem(item.toRow(account))
    }

    internal companion object {
        const val LOCAL_STORE_FAILURE = "LOCAL_STORE_FAILURE"
        const val ACCOUNT_BINDING_MISMATCH = "ACCOUNT_BINDING_MISMATCH"
        const val SUBMISSION_KEY_REUSED = "SUBMISSION_KEY_REUSED"
        const val SUBMISSION_ITEM_MISMATCH = "SUBMISSION_ITEM_MISMATCH"
        const val SUBMISSION_NOT_FOUND = "SUBMISSION_NOT_FOUND"
    }
}

/** UUIDs compare by value (case-insensitive); anything unparsable never matches. */
private fun sameUuid(a: String, b: String): Boolean {
    val left = runCatching { Uuid.parse(a) }.getOrNull() ?: return false
    return left == runCatching { Uuid.parse(b) }.getOrNull()
}

/** Epoch microseconds; sub-microsecond digits are truncated (the server's precision). */
private fun Instant.toEpochMicros(): Long = epochSeconds * 1_000_000 + nanosecondsOfSecond / 1_000

private fun instantOfEpochMicros(us: Long): Instant =
    Instant.fromEpochSeconds(us.floorDiv(1_000_000L), us.mod(1_000_000L) * 1_000)

private fun LocalSubmission.toRow() = Local_submission(
    client_submission_id = clientSubmissionId, source_url = sourceUrl, shared_at_us = sharedAt.toEpochMicros(),
    account_binding = accountBinding, status = submissionStatus.name, retry_after_us = retryAfter?.toEpochMicros(),
    error_kind = lastSubmissionError?.kind?.name, error_code = lastSubmissionError?.code,
    error_request_id = lastSubmissionError?.requestId,
    error_current_version = lastSubmissionError?.currentVersion?.toLong(),
    error_retry_after_seconds = lastSubmissionError?.retryAfterSeconds,
)

private fun Local_submission.toModel() = LocalSubmission(
    clientSubmissionId = client_submission_id, sourceUrl = source_url, sharedAt = instantOfEpochMicros(shared_at_us),
    accountBinding = account_binding, submissionStatus = SubmissionStatus.valueOf(status),
    lastSubmissionError = error_kind?.let {
        ClientError(ErrorKind.valueOf(it), error_code, error_request_id,
            error_current_version?.toInt(), error_retry_after_seconds)
    },
    retryAfter = retry_after_us?.let(::instantOfEpochMicros),
)

private fun WishlistItem.toRow(account: String) = Item_cache(
    account_id = account, item_id = id, version = version.toLong(), client_submission_id = clientSubmissionId,
    source_url = sourceUrl,
    product_name = product.name, product_image_url = product.imageUrl, product_price = product.price?.canonical,
    product_currency = product.currency, product_brand = product.brand, product_merchant = product.merchant,
    product_metadata_checked_at = product.metadataCheckedAtIso,
    product_name_source = product.nameSource?.name, product_image_source = product.imageSource?.name,
    category_id = category.id, category_source = category.source?.name,
    category_missing_reason = category.missingReason?.name, category_name = category.name,
    category_parent_id = category.parentId, category_kind = category.kind,
    purpose_id = purpose.id, purpose_source = purpose.source.name,
    analysis_status = analysis.status.name, analysis_failure_code = analysis.failureCode,
    review_status = reviewStatus.name, lifecycle_status = lifecycleStatus.name,
    required_action = requiredAction.name, created_at = createdAtIso, updated_at = updatedAtIso,
    manual_completion_at = manualCompletionAtIso,
    allowed_actions = allowedActions.map { it.name }.sorted().joinToString(","),
    client_created_at = clientCreatedAtIso,
)

private fun Item_cache.toModel() = WishlistItem(
    id = item_id, clientSubmissionId = client_submission_id, version = version.toInt(), sourceUrl = source_url,
    product = ProductSnapshot(
        name = product_name, imageUrl = product_image_url,
        price = product_price?.let { requireNotNull(DecimalAmount.parseOrNull(it)) },
        currency = product_currency, brand = product_brand, merchant = product_merchant,
        metadataCheckedAt = product_metadata_checked_at?.let(Instant::parse),
        nameSource = product_name_source?.let(ValueSource::valueOf),
        imageSource = product_image_source?.let(ValueSource::valueOf),
    ),
    category = ItemCategory(
        id = category_id, source = category_source?.let(ValueSource::valueOf),
        missingReason = category_missing_reason?.let(CategoryMissingReason::valueOf),
        name = category_name, parentId = category_parent_id, kind = category_kind,
    ),
    purpose = ItemPurpose(purpose_id, ValueSource.valueOf(purpose_source)),
    analysis = ItemAnalysis(AnalysisStatus.valueOf(analysis_status), analysis_failure_code),
    reviewStatus = ReviewStatus.valueOf(review_status),
    lifecycleStatus = LifecycleStatus.valueOf(lifecycle_status),
    requiredAction = RequiredAction.valueOf(required_action),
    createdAt = Instant.parse(created_at), updatedAt = Instant.parse(updated_at),
    manualCompletionAt = manual_completion_at?.let(Instant::parse),
    allowedActions = allowed_actions.split(',').filter { it.isNotEmpty() }.map(ItemAction::valueOf).toSet(),
    clientCreatedAt = client_created_at?.let(Instant::parse),
)
