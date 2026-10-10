package app.analysis

import java.math.BigDecimal
import java.sql.Connection
import java.sql.Types
import java.util.UUID

internal data class StoredMetadata(
    val name: String?, val description: String?, val image: String?, val canonical: String?,
    val brand: String?, val merchant: String?, val price: BigDecimal?, val currency: String?,
    val nameSource: String?, val imageSource: String?, val overrides: Set<String>,
)

internal data class PendingMetadata(
    val name: String?, val description: String?, val image: String?, val canonical: String?,
    val brand: String?, val merchant: String?, val price: BigDecimal?, val currency: String?,
) {
    /** Every saved page read carries its canonical URL; NeedsBrowser saves nothing. */
    val pageRead: Boolean get() = canonical != null
}

internal data class MergedMetadata(
    val name: String?, val description: String?, val image: String?, val canonical: String?,
    val brand: String?, val merchant: String?, val price: BigDecimal?, val currency: String?,
    val nameSource: String?, val imageSource: String?, val recordCheckedAt: Boolean,
) {
    fun assignments(): Map<String, Any?> = linkedMapOf(
        "product_name" to name, "product_description" to description, "product_image_url" to image, "canonical_url" to canonical,
        "name_source" to nameSource, "image_source" to imageSource, "product_brand" to brand, "merchant_name" to merchant,
        "product_price" to price, "product_currency" to currency,
    )
}

/**
 * Success prefers new values, partial/failure only fills gaps; user sources and overrides stay.
 * A price belongs to its check time, so a page read replaces the pair (even with null) together with metadataCheckedAt.
 */
internal fun mergeMetadata(existing: StoredMetadata, pending: PendingMetadata, complete: Boolean): MergedMetadata {
    fun protects(field: String, source: String?) = source == "USER" || field in existing.overrides
    fun merged(old: String?, new: String?, protected: Boolean) =
        if (protected) old else if (complete) new ?: old else old ?: new
    fun source(old: String?, new: String?, current: String?, protected: Boolean) =
        if (!protected && new != null && (complete || old == null)) "AI" else current
    val nameProtected = protects("NAME", existing.nameSource)
    val imageProtected = protects("IMAGE", existing.imageSource)
    val replacePrice = pending.pageRead
    return MergedMetadata(
        name = merged(existing.name, pending.name, nameProtected),
        description = merged(existing.description, pending.description, false),
        image = merged(existing.image, pending.image, imageProtected),
        canonical = merged(existing.canonical, pending.canonical, false),
        brand = merged(existing.brand, pending.brand, "BRAND" in existing.overrides),
        merchant = merged(existing.merchant, pending.merchant, false),
        price = if (replacePrice) pending.price else existing.price,
        currency = if (replacePrice) pending.currency else existing.currency,
        nameSource = source(existing.name, pending.name, existing.nameSource, nameProtected),
        imageSource = source(existing.image, pending.image, existing.imageSource, imageProtected),
        recordCheckedAt = pending.pageRead,
    )
}

internal fun Connection.readStoredMetadata(itemId: UUID): StoredMetadata = prepareStatement("""
    select product_name,product_description,product_image_url,canonical_url,product_brand,merchant_name,product_price,product_currency,
           name_source,image_source,user_override_fields from wishlist_items where id=?
""".trimIndent()).use { s ->
    s.setObject(1, itemId)
    s.executeQuery().use { r ->
        check(r.next())
        val overrides = r.getArray("user_override_fields")
        val fields = try { (overrides.array as Array<*>).map { it as String }.toSet() } finally { overrides.free() }
        StoredMetadata(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6),
            r.getBigDecimal(7), r.getString(8), r.getString(9), r.getString(10), fields)
    }
}

internal fun Connection.readPendingMetadata(jobId: UUID): PendingMetadata = prepareStatement("""
    select pending_product_name,pending_product_description,pending_product_image_url,pending_canonical_url,
           pending_brand,pending_merchant,pending_price,pending_currency from analysis_jobs where id=?
""".trimIndent()).use { s ->
    s.setObject(1, jobId)
    s.executeQuery().use { r ->
        check(r.next())
        PendingMetadata(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6),
            r.getBigDecimal(7), r.getString(8))
    }
}

private val UUID_COLUMNS = setOf("custom_category_id", "purpose_id")

/**
 * One UPDATE with a single DB clock read for updated_at, metadata_checked_at and classified_at; bumps version once.
 * Caller holds the item lock and owns the transaction.
 */
internal fun Connection.updateAnalyzedItem(itemId: UUID, values: Map<String, Any?>, recordCheckedAt: Boolean, classified: Boolean) {
    val assignments = values.keys.joinToString { if (it in UUID_COLUMNS) "$it=?::uuid" else "$it=?" }
    prepareStatement("""
        with now as (select clock_timestamp() t)
        update wishlist_items set $assignments,
            metadata_checked_at=case when ? then now.t else metadata_checked_at end,
            classified_at=case when ? then now.t else classified_at end,
            version=version+1, updated_at=now.t
        from now where id=?
    """.trimIndent()).use { s ->
        var parameter = 1
        values.values.forEach { value ->
            when (value) {
                null -> s.setNull(parameter++, Types.NULL)
                is BigDecimal -> s.setBigDecimal(parameter++, value)
                else -> s.setString(parameter++, value as String)
            }
        }
        s.setBoolean(parameter++, recordCheckedAt); s.setBoolean(parameter++, classified); s.setObject(parameter, itemId)
        check(s.executeUpdate() == 1)
    }
}
