package app.budget

/** This release's approved price table; a changed model price requires a new release review. */
class PriceTable(
    val version: String = APPROVED_VERSION,
    val inputUsdPerMillion: Double = 0.20,
    val outputUsdPerMillion: Double = 1.20,
) {
    init {
        require(version == APPROVED_VERSION && inputUsdPerMillion == 0.20 && outputUsdPerMillion == 1.20) {
            "Unapproved LLM price table"
        }
    }

    fun maximumMicrousd(): Long = costMicrousd(2000, 80)

    fun costMicrousd(inputTokens: Int, outputTokens: Int): Long {
        require(inputTokens in 0..2000 && outputTokens in 0..80)
        return kotlin.math.ceil(inputTokens * inputUsdPerMillion + outputTokens * outputUsdPerMillion).toLong()
    }

    companion object { const val APPROVED_VERSION = "gpt-5.6-luna-2026-09-23" }
}
