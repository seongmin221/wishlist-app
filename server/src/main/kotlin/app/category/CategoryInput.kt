package app.category

import app.text.UserTextRules

data class CategoryInput(val name: String, val description: String?, val examples: List<String>)

object CategoryInputPolicy {
    fun validate(input: CategoryInput): Set<String> = buildSet {
        if (!UserTextRules.valid(input.name, 40) || UserTextRules.isBlank(input.name)) add("name")
        if (input.description != null && !UserTextRules.valid(input.description, 200, multiline = true)) add("description")
        if (input.examples.size > 5 || input.examples.any { !UserTextRules.valid(it, 60) || UserTextRules.isBlank(it) }) add("examples")
    }

    /** Preserve display text; normalize only the unique comparison key. */
    fun normalizedName(name: String): String = UserTextRules.normalizedKey(name)
}
