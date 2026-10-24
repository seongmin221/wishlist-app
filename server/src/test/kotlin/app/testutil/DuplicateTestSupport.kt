package app.testutil

import app.ai.ClassificationResult
import app.analysis.AnalysisClaim
import app.analysis.AnalysisPendingResultRepository
import app.analysis.GeneralWorkerService
import app.analysis.ProcessingOutcome
import app.extraction.GeneralExtractionProcessor
import app.extraction.HttpFetchResponse
import app.extraction.HttpMetadataExtractor
import app.extraction.Metadata
import app.extraction.UrlSafetyPolicy
import app.wishlist.CreateWishlistItemService
import java.net.InetAddress
import java.util.UUID
import javax.sql.DataSource

/** JSON-LD product page with optional seller-independent identifiers. */
fun identifiedPage(name: String, price: String = "129000", brand: String? = "HOKA", gtin: String? = null, mpn: String? = null,
                   canonical: String? = null): String = MetadataFixtures.page(buildString {
    if (canonical != null) append("""<link rel="canonical" href="$canonical">""")
    append(MetadataFixtures.jsonLd(buildString {
        append("""{"@type":"Product","name":"$name","offers":{"@type":"Offer","price":"$price","priceCurrency":"KRW"}""")
        if (brand != null) append(""","brand":"$brand"""")
        if (gtin != null) append(""","gtin13":"$gtin"""")
        if (mpn != null) append(""","mpn":"$mpn"""")
        append("}")
    }))
})

/**
 * Real create → claim → extract → classify → finish path with a counting fake network. Hosts named `blocked.*`
 * resolve to a private address. The default classifier assigns C026 without a purpose judgement.
 */
class AnalysisPipeline(private val source: DataSource) {
    val pages = mutableMapOf<String, String>()
    val redirects = mutableMapOf<String, String>()
    val fetched = mutableListOf<String>()
    var classify: (AnalysisClaim, Metadata) -> ProcessingOutcome = { claim, _ ->
        check(AnalysisPendingResultRepository(source).saveAssignment(claim, ClassificationResult.Assigned("C026", null)))
        ProcessingOutcome.Complete
    }
    private val safety = UrlSafetyPolicy { host ->
        listOf(InetAddress.getByName(if (host.startsWith("blocked.")) "10.0.0.1" else "93.184.215.14"))
    }
    private val extractor = HttpMetadataExtractor(safety) { url, _ ->
        fetched += url
        redirects[url]?.let { HttpFetchResponse(301, mapOf("Location" to it), "") }
            ?: pages[url]?.let { HttpFetchResponse(200, mapOf("content-type" to "text/html"), it) }
            ?: HttpFetchResponse(404, emptyMap(), "")
    }

    fun save(owner: UUID, url: String, key: UUID = UUID.randomUUID()): UUID =
        CreateWishlistItemService(source).create(owner, key, url).createdItemId

    /** Runs the item's newest general job once. */
    fun run(item: UUID) {
        val (job, generation) = source.connection.use { c ->
            c.prepareStatement("select id, generation from analysis_jobs where wishlist_item_id=? order by generation desc limit 1").use { s ->
                s.setObject(1, item); s.executeQuery().use { r -> check(r.next()); r.getObject(1, UUID::class.java) to r.getInt(2) }
            }
        }
        val processor = GeneralExtractionProcessor(source, extractor::extract) { claim, metadata -> classify(claim, metadata) }
        GeneralWorkerService(source, processor::process).runGeneral(job, generation)
    }

    fun saveAndRun(owner: UUID, url: String): UUID = save(owner, url).also(::run)
}

fun itemText(source: DataSource, item: UUID, column: String): String? =
    analysisScalar(source, "select $column::text from wishlist_items where id='$item'")

/** A stored item with a canonical URL, as an analysis finish would leave it. */
fun seedAnalyzedItem(source: DataSource, owner: UUID, seed: ItemSeed = ItemSeed(), canonical: String = "https://shop.example/p"): UUID =
    seedItem(source, owner, seed).also { analysisSql(source, "update wishlist_items set canonical_url='$canonical' where id='$it'") }

/** Inserts an OPEN pair directly; detection itself is covered by DuplicateDetectionTest. */
fun seedPair(source: DataSource, owner: UUID, new: UUID, existing: UUID, bases: String = "CANONICAL_URL,SOURCE_URL"): UUID {
    val id = UUID.randomUUID()
    analysisSql(source, """insert into duplicate_candidates(id,owner_id,new_item_id,existing_item_id,bases)
        values ('$id','$owner','$new','$existing',string_to_array('$bases',','))""")
    return id
}

fun pairColumns(source: DataSource, pair: UUID): String? =
    analysisScalar(source, "select status||' '||coalesce(decision,'-')||' '||version from duplicate_candidates where id='$pair'")
