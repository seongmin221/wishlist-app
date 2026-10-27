package app.product

import app.extraction.Metadata
import java.math.BigDecimal
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** A reusable READY extraction; [extractedAt] is the time its facts (including the price) were read. */
data class CachedProduct(val id: UUID, val metadata: Metadata, val extractedAt: Instant)

/** Shared Product cache. Only new-save analysis reads it; nothing syncs it with items. */
object ProductCacheRepository {
    const val TTL_DAYS = 7

    /** The freshest unexpired entry for a normalized share URL, via its alias or as the canonical URL itself. */
    fun lookup(c: Connection, normalizedSourceUrl: String): CachedProduct? = c.prepareStatement("""
        select p.id, p.canonical_url, p.product_name, p.product_description, p.product_image_url, p.product_brand,
               p.product_price, p.product_currency, p.merchant_name, p.product_gtin, p.product_mpn_key, p.extracted_at
        from products p
        where (p.canonical_url = ? or p.id = (select product_id from product_url_aliases where normalized_source_url = ?))
          and p.extracted_at > clock_timestamp() - interval '$TTL_DAYS days'
        order by p.extracted_at desc, p.id
        limit 1
    """.trimIndent()).use { s ->
        s.setString(1, normalizedSourceUrl); s.setString(2, normalizedSourceUrl)
        s.executeQuery().use { r -> if (r.next()) map(r) else null }
    }

    /** Records a fresh complete extraction under its canonical URL and points the share URL's alias at it. */
    fun upsert(c: Connection, normalizedSourceUrl: String, metadata: Metadata): UUID {
        val name = requireNotNull(metadata.title?.takeIf { it.isNotBlank() }) { "only titled extractions are cached" }
        val pair = metadata.price != null && metadata.currency != null
        val id = c.prepareStatement("""
            insert into products(id, canonical_url, product_name, product_description, product_image_url, product_brand,
                product_price, product_currency, merchant_name, product_gtin, product_mpn_key, extracted_at)
            values (?,?,?,?,?,?,?,?,?,?,?,clock_timestamp())
            on conflict (canonical_url) do update set product_name=excluded.product_name,
                product_description=excluded.product_description, product_image_url=excluded.product_image_url,
                product_brand=excluded.product_brand, product_price=excluded.product_price, product_currency=excluded.product_currency,
                merchant_name=excluded.merchant_name, product_gtin=excluded.product_gtin, product_mpn_key=excluded.product_mpn_key,
                extracted_at=excluded.extracted_at, updated_at=excluded.extracted_at
            returning id
        """.trimIndent()).use { s ->
            s.setObject(1, UUID.randomUUID()); s.setString(2, metadata.canonicalUrl); s.setString(3, name)
            s.setString(4, metadata.description); s.setString(5, metadata.imageUrl); s.setString(6, metadata.brand)
            s.setBigDecimal(7, metadata.price.takeIf { pair }); s.setString(8, metadata.currency.takeIf { pair })
            s.setString(9, metadata.merchant); s.setString(10, metadata.gtin); s.setString(11, metadata.mpnKey)
            s.executeQuery().use { r -> check(r.next()); r.getObject(1, UUID::class.java) }
        }
        c.prepareStatement("""
            insert into product_url_aliases(normalized_source_url, product_id) values (?,?)
            on conflict (normalized_source_url) do update set product_id=excluded.product_id, updated_at=clock_timestamp()
        """.trimIndent()).use { s -> s.setString(1, normalizedSourceUrl); s.setObject(2, id); check(s.executeUpdate() == 1) }
        return id
    }

    private fun map(r: ResultSet) = CachedProduct(
        r.getObject("id", UUID::class.java),
        Metadata(r.getString("product_name"), r.getString("product_description"), r.getString("product_image_url"),
            r.getString("canonical_url"), r.getString("product_brand"), r.getObject("product_price") as BigDecimal?,
            r.getString("product_currency"), r.getString("merchant_name"), r.getString("product_gtin"), r.getString("product_mpn_key")),
        r.getTimestamp("extracted_at").toInstant(),
    )
}
