package app.purpose

import app.text.UserTextRules

data class PurposeInput(val name: String, val description: String?, val color: PurposeColor, val icon: PurposeIcon)

object PurposeInputPolicy {
    const val NAME_MAX = 40
    const val DESCRIPTION_MAX = 200

    fun validate(name: String?, description: String?): Set<String> = buildSet {
        if (!validateName(name)) add("name")
        if (!validateDescription(description)) add("description")
    }

    fun validateName(name: String?): Boolean = name != null && UserTextRules.valid(name, NAME_MAX) && !UserTextRules.isBlank(name)

    /** Description is optional; null means none. */
    fun validateDescription(description: String?): Boolean =
        description == null || UserTextRules.valid(description, DESCRIPTION_MAX, multiline = true)
}
