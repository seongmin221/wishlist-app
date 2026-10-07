package app.category

/** Local conservative screening, not a semantic moderation model. Never edits saved user text. */
object CategoryAiSafetyPolicy {
    private val instructions=Regex("ignore.{0,30}(previous|prior|instructions)|system\\s*prompt|이전.{0,15}지시.{0,15}무시|지시.{0,10}무시|<\\|.*?\\|>",RegexOption.IGNORE_CASE)
    private val code=Regex("https?://|www\\.|<script|javascript:|```|\\b(eval|exec)\\s*\\(|<[^>]+>",RegexOption.IGNORE_CASE)
    private val harmful=Regex("kill\\s+(yourself|all)|자살.{0,10}(방법|해라)|살해.{0,10}(방법|해라)",RegexOption.IGNORE_CASE)
    fun exclusionReason(input:CategoryInput):String? {
        val text=(listOf(input.name,input.description.orEmpty())+input.examples).joinToString("\n")
        return when {
            instructions.containsMatchIn(text) -> "INSTRUCTION_TEXT"
            code.containsMatchIn(text) -> "URL_OR_CODE"
            harmful.containsMatchIn(text) -> "HARMFUL_TEXT"
            else -> null
        }
    }
}
