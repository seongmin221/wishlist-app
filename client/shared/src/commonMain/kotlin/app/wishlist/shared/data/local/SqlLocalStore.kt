package app.wishlist.shared.data.local

import app.cash.sqldelight.db.SqlDriver
import app.wishlist.shared.core.*
import app.wishlist.shared.model.*
import app.wishlist.shared.repository.LocalStore
import kotlinx.coroutines.CancellationException
import kotlin.time.Instant

/**
 * Single-process SQLite store. Lock order is session gate -> DB transaction; only the short DB
 * commit runs inside the gate. The account is always taken from the validated snapshot, never
 * from the caller's arbitrary value. Any non-cancellation exception becomes LOCAL_STORE_FAILURE.
 */
internal class SqlLocalStore(
    private val session: AuthSession,
    driver: SqlDriver,
    /** Test-only seam: runs inside the accept transaction after the cache write. */
    private val afterAcceptCacheWrite: () -> Unit = {},
) : LocalStore {
    private val database = WishlistDatabase(driver)
    private val queries = database.wishlistQueries

    private fun <T> failure(kind: ErrorKind, code: String? = null): ClientResult<T> =
        ClientResult.Failure(ClientError(kind, code))

    private suspend fun <T> gated(snapshot: SessionSnapshot, block: () -> ClientResult<T>): ClientResult<T> = try {
        session.withCurrent(snapshot) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        failure(ErrorKind.UNAVAILABLE, LOCAL_STORE_FAILURE)
    }

    private suspend fun <T> gatedForAccount(
        snapshot: SessionSnapshot,
        block: (accountId: String) -> ClientResult<T>,
    ): ClientResult<T> = gated(snapshot) {
        val accountId = snapshot.accountId ?: return@gated failure(ErrorKind.UNAUTHENTICATED)
        block(accountId)
    }

    override suspend fun saveSubmission(submission: LocalSubmission): ClientResult<Unit> {
        val snapshot = session.state.value
        return gated(snapshot) {
            val binding = submission.accountBinding
            if (binding != null && binding != snapshot.accountId) {
                failure(ErrorKind.VALIDATION, ACCOUNT_BINDING_MISMATCH)
            } else {
                database.transactionWithResult<ClientResult<Unit>> {
                    val existing = queries.selectSubmission(submission.clientSubmissionId).executeAsOneOrNull()
                    // An existing binding may only be kept, never rebound or cleared.
                    if (existing?.account_binding != null && existing.account_binding != binding) {
                        failure(ErrorKind.VALIDATION, ACCOUNT_BINDING_MISMATCH)
                    } else {
                        queries.insertSubmission(submission.toRow())
                        ClientResult.Success(Unit)
                    }
                }
            }
        }
    }

    override suspend fun pending(): ClientResult<List<LocalSubmission>> {
        val snapshot = session.state.value
        return gated(snapshot) {
            val account = snapshot.accountId
            val rows = if (account == null) queries.selectUnboundSubmissions().executeAsList()
            else queries.selectVisibleSubmissions(account).executeAsList()
            ClientResult.Success(rows.map { it.toModel() })
        }
    }

    override suspend fun upsertItem(snapshot: SessionSnapshot, item: WishlistItem): ClientResult<Unit> =
        gatedForAccount(snapshot) { account ->
            database.transaction { upsertIfNewer(account, item) }
            ClientResult.Success(Unit)
        }

    override suspend fun cachedItem(snapshot: SessionSnapshot, id: String): ClientResult<WishlistItem?> =
        gatedForAccount(snapshot) { account ->
            ClientResult.Success(queries.selectItem(account, id).executeAsOneOrNull()?.toModel())
        }

    override suspend fun accept(snapshot: SessionSnapshot, submissionId: String, item: WishlistItem): ClientResult<Unit> =
        gatedForAccount(snapshot) { account ->
            database.transactionWithResult<ClientResult<Unit>> {
                val pending = queries.selectSubmission(submissionId).executeAsOneOrNull()
                    ?: return@transactionWithResult failure(ErrorKind.NOT_FOUND, "SUBMISSION_NOT_FOUND")
                if (pending.account_binding != account) {
                    return@transactionWithResult failure(ErrorKind.VALIDATION, ACCOUNT_BINDING_MISMATCH)
                }
                if (item.lifecycleStatus == LifecycleStatus.DELETED) {
                    queries.deleteItemThrough(account, item.id, item.version.toLong())
                } else {
                    upsertIfNewer(account, item)
                }
                afterAcceptCacheWrite()
                queries.deleteSubmission(submissionId)
                ClientResult.Success(Unit)
            }
        }

    override suspend fun removeCachedItem(snapshot: SessionSnapshot, id: String, throughVersion: Int): ClientResult<Unit> =
        gatedForAccount(snapshot) { account ->
            queries.deleteItemThrough(account, id, throughVersion.toLong())
            ClientResult.Success(Unit)
        }

    override suspend fun clearCurrentCache(): ClientResult<Unit> {
        val snapshot = session.state.value
        return gated(snapshot) {
            snapshot.accountId?.let { queries.deleteAccountItems(it) }
            ClientResult.Success(Unit)
        }
    }

    private fun upsertIfNewer(account: String, item: WishlistItem) {
        val existing = queries.selectItem(account, item.id).executeAsOneOrNull()?.version
        if (existing == null || item.version > existing) queries.insertItem(item.toRow(account))
    }

    internal companion object {
        const val LOCAL_STORE_FAILURE = "LOCAL_STORE_FAILURE"
        const val ACCOUNT_BINDING_MISMATCH = "ACCOUNT_BINDING_MISMATCH"
    }
}

private fun LocalSubmission.toRow() = Local_submission(
    client_submission_id = clientSubmissionId, source_url = sourceUrl, created_at = createdAtIso,
    account_binding = accountBinding, status = submissionStatus.name, server_item_id = serverItemId,
    error_kind = lastSubmissionError?.kind?.name, error_code = lastSubmissionError?.code,
    error_request_id = lastSubmissionError?.requestId,
    error_current_version = lastSubmissionError?.currentVersion?.toLong(),
    error_retry_after_seconds = lastSubmissionError?.retryAfterSeconds,
)

private fun Local_submission.toModel() = LocalSubmission(
    clientSubmissionId = client_submission_id, sourceUrl = source_url, createdAt = Instant.parse(created_at),
    accountBinding = account_binding, submissionStatus = SubmissionStatus.valueOf(status),
    serverItemId = server_item_id,
    lastSubmissionError = error_kind?.let {
        ClientError(ErrorKind.valueOf(it), error_code, error_request_id,
            error_current_version?.toInt(), error_retry_after_seconds)
    },
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
