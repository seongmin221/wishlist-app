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

    fun maximumMicrousd(): Long = costMicrousd(MAX_INPUT_TOKENS, MAX_OUTPUT_TOKENS)

    fun costMicrousd(inputTokens: Int, outputTokens: Int): Long {
        require(inputTokens in 0..MAX_INPUT_TOKENS && outputTokens in 0..MAX_OUTPUT_TOKENS)
        return kotlin.math.ceil(inputTokens * inputUsdPerMillion + outputTokens * outputUsdPerMillion).toLong()
    }

    companion object {
        const val APPROVED_VERSION = "gpt-5.6-luna-2026-09-23"
        /** B3 decision: input 2,500 (was 2,000); ceilings scale so call capacity stays the same. */
        const val MAX_INPUT_TOKENS = 2500
        const val MAX_OUTPUT_TOKENS = 80
    }
}
