package app.duplicates

/** Identity relations stored on a pair, in this order. SOURCE_URL is a notice relation, not product identity. */
enum class DuplicateBasis { CANONICAL_URL, GTIN, BRAND_MPN, SOURCE_URL }

enum class DuplicateKind { MATCH, SAME_URL, AWAITING_ANALYSIS }

enum class DuplicateDecision { KEEP_BOTH, DELETE_NEW, DELETE_EXISTING }

/** Visible OPEN pairs of a new item by derived kind; null on items when all three are zero. */
data class DuplicateCounts(val match: Int, val sameUrl: Int, val awaitingAnalysis: Int)

/**
 * The pair kind follows both items' live analysis state, because states also change outside analysis finish
 * (exhaustion, lease recovery, reanalysis start). [kindSql] and [kindOf] are the same table; keep them in step.
 */
object DuplicateKinds {
    private val IDENTITY = setOf(DuplicateBasis.CANONICAL_URL, DuplicateBasis.GTIN, DuplicateBasis.BRAND_MPN)
    private val URL = setOf(DuplicateBasis.SOURCE_URL, DuplicateBasis.CANONICAL_URL)

    fun kindOf(newStatus: String, newCanonical: String?, existingStatus: String, bases: Set<DuplicateBasis>): DuplicateKind? {
        if (newStatus !in setOf("READY", "PARTIAL") || newCanonical == null) return null
        return when {
            existingStatus == "PROCESSING" -> DuplicateKind.AWAITING_ANALYSIS
            existingStatus in setOf("READY", "PARTIAL") && bases.any { it in IDENTITY } -> DuplicateKind.MATCH
            existingStatus in setOf("FAILED_RETRYABLE", "FAILED_TERMINAL") && bases.any { it in URL } -> DuplicateKind.SAME_URL
            else -> null
        }
    }

    fun allowedDecisions(kind: DuplicateKind?): List<DuplicateDecision> =
        if (kind == DuplicateKind.MATCH || kind == DuplicateKind.SAME_URL) DuplicateDecision.entries else emptyList()

    /** SQL text of [kindOf] over a new item [n], an existing item [e] and a pair [d]; null when hidden. */
    fun kindSql(n: String, e: String, d: String): String = """case when $n.analysis_status in ('READY','PARTIAL') and $n.canonical_url is not null then
        case when $e.analysis_status = 'PROCESSING' then 'AWAITING_ANALYSIS'
             when $e.analysis_status in ('READY','PARTIAL') and $d.bases && array['CANONICAL_URL','GTIN','BRAND_MPN']::text[] then 'MATCH'
             when $e.analysis_status in ('FAILED_RETRYABLE','FAILED_TERMINAL') and $d.bases && array['SOURCE_URL','CANONICAL_URL']::text[] then 'SAME_URL'
        end end"""
}
